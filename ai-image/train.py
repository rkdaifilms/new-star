"""Train New Star's small unconditional DDPM image model from permitted images."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from pathlib import Path

import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader

from data import ImageFolderDataset, audit_image_dataset
from model import DDPMSchedule, NewStarImageUNet


def atomic_save(payload: dict, destination: str | Path) -> None:
    path = Path(destination)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            torch.save(payload, stream)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def train(args) -> dict:
    if min(args.steps, args.batch_size, args.image_size, args.base_channels, args.diffusion_steps) < 1:
        raise ValueError("steps, batch_size, image_size, base_channels and diffusion_steps must be positive")
    if args.image_size < 8 or args.image_size % 4:
        raise ValueError("image_size must be at least 8 and divisible by 4")
    if args.base_channels < 8 or args.diffusion_steps < 2:
        raise ValueError("base_channels must be >= 8 and diffusion_steps must be >= 2")
    if args.learning_rate <= 0:
        raise ValueError("learning_rate must be positive")
    if not isinstance(args.seed, int) or isinstance(args.seed, bool) or not 0 <= args.seed <= 2**63 - 1:
        raise ValueError("seed must be an integer between 0 and 2^63-1")
    device = torch.device(args.device)
    if device.type not in {"cpu", "cuda"}:
        raise ValueError("device must be cpu, cuda, or cuda:N")
    if device.type == "cuda":
        if not torch.cuda.is_available():
            raise ValueError("CUDA requested but unavailable")
        if device.index is not None and device.index >= torch.cuda.device_count():
            raise ValueError("requested CUDA device index is unavailable")
    audit = audit_image_dataset(args.data, args.manifest)
    dataset = ImageFolderDataset(args.data, args.image_size)
    loader = DataLoader(
        dataset, batch_size=args.batch_size, shuffle=True,
        num_workers=0, drop_last=False,
    )
    torch.manual_seed(args.seed)
    if device.type == "cuda":
        torch.cuda.manual_seed_all(args.seed)
    model = NewStarImageUNet(args.base_channels).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate)
    schedule = DDPMSchedule(args.diffusion_steps).to(device)
    iterator = iter(loader)
    losses = []
    model.train()
    for step in range(args.steps):
        try:
            clean = next(iterator)
        except StopIteration:
            iterator = iter(loader)
            clean = next(iterator)
        clean = clean.to(device)
        times = torch.randint(
            0, schedule.steps, (clean.shape[0],), device=device
        )
        noise = torch.randn(clean.shape, device=device)
        noisy = schedule.add_noise(clean, times, noise)
        optimizer.zero_grad(set_to_none=True)
        prediction = model(noisy, times)
        loss = F.mse_loss(prediction, noise)
        if not torch.isfinite(loss):
            raise RuntimeError("training loss became non-finite")
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        losses.append(float(loss.detach().item()))
        if (step + 1) % max(1, args.log_interval) == 0 or step + 1 == args.steps:
            print(json.dumps({
                "step": step + 1,
                "steps": args.steps,
                "loss": losses[-1],
                "mean_loss": sum(losses) / len(losses),
            }), flush=True)
    data_digest = hashlib.sha256()
    for item in audit["images"]:
        data_digest.update(item["file"].encode("utf-8"))
        data_digest.update(item["sha256"].encode("ascii"))
    checkpoint = {
        "format": "new-star-ddpm-image-v1",
        "model_config": {
            "base_channels": args.base_channels,
            "image_size": args.image_size,
            "diffusion_steps": args.diffusion_steps,
        },
        "state_dict": model.cpu().state_dict(),
        "dataset_audit": audit,
        "dataset_fingerprint": data_digest.hexdigest(),
        "training": {
            "steps": args.steps,
            "batch_size": args.batch_size,
            "learning_rate": args.learning_rate,
            "seed": args.seed,
            "final_loss": losses[-1],
            "mean_loss": sum(losses) / len(losses),
            "device": str(device),
        },
    }
    atomic_save(checkpoint, args.output)
    return checkpoint


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True, help="Folder of permitted training images")
    parser.add_argument("--manifest", required=True, help="JSON provenance and permission manifest")
    parser.add_argument("--output", default="artifacts/newstar-image-ddpm.pt")
    parser.add_argument("--steps", type=int, default=10000)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--image-size", type=int, choices=(32, 64), default=32)
    parser.add_argument("--base-channels", type=int, default=32)
    parser.add_argument("--diffusion-steps", type=int, default=100)
    parser.add_argument("--learning-rate", type=float, default=2e-4)
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--log-interval", type=int, default=100)
    parser.add_argument("--device", default="cpu")
    args = parser.parse_args()
    if args.log_interval < 1:
        parser.error("--log-interval must be positive")
    try:
        train(args)
    except (ValueError, OSError, RuntimeError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    main()
