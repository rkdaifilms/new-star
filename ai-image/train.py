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


def dataset_fingerprint(audit: dict) -> str:
    digest = hashlib.sha256()
    for item in audit["images"]:
        digest.update(item["file"].encode("utf-8"))
        digest.update(item["sha256"].encode("ascii"))
    return digest.hexdigest()


def training_config(args) -> dict:
    return {
        "batch_size": args.batch_size,
        "image_size": args.image_size,
        "base_channels": args.base_channels,
        "diffusion_steps": args.diffusion_steps,
        "learning_rate": args.learning_rate,
        "ema_decay": args.ema_decay,
        "seed": args.seed,
    }


def update_ema(ema_state: dict[str, torch.Tensor], model: torch.nn.Module,
               decay: float) -> None:
    with torch.no_grad():
        for name, value in model.state_dict().items():
            current = value.detach()
            if torch.is_floating_point(ema_state[name]):
                ema_state[name].mul_(decay).add_(current, alpha=1.0 - decay)
            else:
                ema_state[name].copy_(current)


def make_checkpoint(model, optimizer, ema_state: dict[str, torch.Tensor],
                    completed_steps: int, losses: list[float],
                    audit: dict, fingerprint: str, config: dict,
                    order: torch.Tensor, offset: int,
                    shuffle_generator: torch.Generator,
                    noise_generator: torch.Generator) -> dict:
    return {
        "format": "new-star-ddpm-image-resume-v1",
        "model_config": {
            "base_channels": config["base_channels"],
            "image_size": config["image_size"],
            "diffusion_steps": config["diffusion_steps"],
        },
        "training_config": config,
        "state_dict": {key: value.detach().cpu() for key, value in model.state_dict().items()},
        "ema_state_dict": {key: value.detach().cpu() for key, value in ema_state.items()},
        "optimizer_state": optimizer.state_dict(),
        "completed_steps": completed_steps,
        "losses": losses,
        "dataset_audit": audit,
        "dataset_fingerprint": fingerprint,
        "sampler": {
            "order": order.tolist(),
            "offset": offset,
            "generator_state": shuffle_generator.get_state(),
        },
        "noise_generator_state": noise_generator.get_state(),
    }


def train(args) -> dict:
    if min(args.steps, args.batch_size, args.image_size, args.base_channels, args.diffusion_steps) < 1:
        raise ValueError("steps, batch_size, image_size, base_channels and diffusion_steps must be positive")
    if args.image_size < 8 or args.image_size % 4:
        raise ValueError("image_size must be at least 8 and divisible by 4")
    if args.base_channels < 8 or args.diffusion_steps < 2:
        raise ValueError("base_channels must be >= 8 and diffusion_steps must be >= 2")
    if args.learning_rate <= 0:
        raise ValueError("learning_rate must be positive")
    if not 0.0 <= args.ema_decay < 1.0:
        raise ValueError("ema_decay must be in the range [0, 1)")
    if not isinstance(args.seed, int) or isinstance(args.seed, bool) or not 0 <= args.seed <= 2**63 - 1:
        raise ValueError("seed must be an integer between 0 and 2^63-1")
    if args.checkpoint_interval < 1:
        raise ValueError("checkpoint_interval must be positive")
    device = torch.device(args.device)
    if device.type not in {"cpu", "cuda"}:
        raise ValueError("device must be cpu, cuda, or cuda:N")
    if device.type == "cuda":
        if not torch.cuda.is_available():
            raise ValueError("CUDA requested but unavailable")
        if device.index is not None and device.index >= torch.cuda.device_count():
            raise ValueError("requested CUDA device index is unavailable")

    audit = audit_image_dataset(args.data, args.manifest)
    fingerprint = dataset_fingerprint(audit)
    dataset = ImageFolderDataset(args.data, args.image_size)
    config = training_config(args)

    torch.manual_seed(args.seed)
    if device.type == "cuda":
        torch.cuda.manual_seed_all(args.seed)
    model = NewStarImageUNet(args.base_channels).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate)
    ema_state = {key: value.detach().clone() for key, value in model.state_dict().items()}
    schedule = DDPMSchedule(args.diffusion_steps).to(device)
    shuffle_generator = torch.Generator(device="cpu").manual_seed(args.seed + 1)
    noise_generator = torch.Generator(device=device).manual_seed(args.seed + 2)
    order = torch.randperm(len(dataset), generator=shuffle_generator)
    offset = 0
    completed_steps = 0
    losses: list[float] = []

    if args.resume_from:
        resume_path = Path(args.resume_from)
        if not resume_path.is_file():
            raise ValueError("resume checkpoint does not exist")
        resume = torch.load(resume_path, map_location=device, weights_only=True)
        if not isinstance(resume, dict) or resume.get("format") != "new-star-ddpm-image-resume-v1":
            raise ValueError("unsupported image training resume checkpoint")
        if resume.get("dataset_fingerprint") != fingerprint:
            raise ValueError("resume dataset fingerprint does not match the current image dataset")
        if resume.get("training_config") != config:
            raise ValueError("resume training configuration does not match the current run")
        completed_steps = resume.get("completed_steps")
        if not isinstance(completed_steps, int) or completed_steps < 1 or completed_steps > args.steps:
            raise ValueError("resume completed_steps must be between one and the requested total steps")
        model.load_state_dict(resume["state_dict"])
        saved_ema = resume.get("ema_state_dict")
        if not isinstance(saved_ema, dict) or saved_ema.keys() != model.state_dict().keys():
            raise ValueError("resume checkpoint is missing a compatible EMA state")
        for key, value in saved_ema.items():
            if value.shape != ema_state[key].shape or value.dtype != ema_state[key].dtype:
                raise ValueError("resume EMA tensor shape or dtype is invalid")
            ema_state[key].copy_(value.to(device))
        optimizer.load_state_dict(resume["optimizer_state"])
        losses = resume.get("losses")
        if not isinstance(losses, list) or len(losses) != completed_steps:
            raise ValueError("resume loss history does not match completed_steps")
        sampler = resume.get("sampler")
        if not isinstance(sampler, dict):
            raise ValueError("resume checkpoint is missing sampler state")
        restored_order = sampler.get("order")
        restored_offset = sampler.get("offset")
        if (not isinstance(restored_order, list) or len(restored_order) != len(dataset)
                or sorted(restored_order) != list(range(len(dataset)))):
            raise ValueError("resume sampler order is invalid for the current dataset")
        if not isinstance(restored_offset, int) or not 0 <= restored_offset <= len(dataset):
            raise ValueError("resume sampler offset is invalid")
        order = torch.tensor(restored_order, dtype=torch.long)
        offset = restored_offset
        shuffle_generator.set_state(sampler["generator_state"])
        noise_generator.set_state(resume["noise_generator_state"])

    resume_output = Path(args.output).with_name(Path(args.output).name + ".resume.pt")
    model.train()
    while completed_steps < args.steps:
        if offset >= len(dataset):
            order = torch.randperm(len(dataset), generator=shuffle_generator)
            offset = 0
        indices = order[offset:offset + args.batch_size].tolist()
        offset += len(indices)
        clean = torch.stack([dataset[index] for index in indices]).to(device)
        times = torch.randint(
            0, schedule.steps, (clean.shape[0],), device=device,
            generator=noise_generator,
        )
        noise = torch.randn(clean.shape, device=device, generator=noise_generator)
        noisy = schedule.add_noise(clean, times, noise)
        optimizer.zero_grad(set_to_none=True)
        prediction = model(noisy, times)
        loss = F.mse_loss(prediction, noise)
        if not torch.isfinite(loss):
            raise RuntimeError("training loss became non-finite")
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        update_ema(ema_state, model, args.ema_decay)
        completed_steps += 1
        losses.append(float(loss.detach().item()))

        if completed_steps % args.log_interval == 0 or completed_steps == args.steps:
            print(json.dumps({
                "step": completed_steps,
                "steps": args.steps,
                "loss": losses[-1],
                "mean_loss": sum(losses) / len(losses),
            }), flush=True)

        if completed_steps % args.checkpoint_interval == 0 or completed_steps == args.steps:
            resume_state = make_checkpoint(
                model, optimizer, ema_state, completed_steps, losses, audit, fingerprint,
                config, order, offset, shuffle_generator, noise_generator,
            )
            atomic_save(resume_state, resume_output)

    model_config = {
        "base_channels": args.base_channels,
        "image_size": args.image_size,
        "diffusion_steps": args.diffusion_steps,
    }
    checkpoint = {
        "format": "new-star-ddpm-image-v1",
        "model_config": model_config,
        "state_dict": {key: value.detach().cpu() for key, value in model.state_dict().items()},
        "ema_state_dict": {key: value.detach().cpu() for key, value in ema_state.items()},
        "dataset_audit": audit,
        "dataset_fingerprint": fingerprint,
        "training": {
            "steps": completed_steps,
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
    parser.add_argument("--steps", type=int, default=10000, help="Desired total optimizer steps")
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--image-size", type=int, choices=(32, 64), default=32)
    parser.add_argument("--base-channels", type=int, default=32)
    parser.add_argument("--diffusion-steps", type=int, default=100)
    parser.add_argument("--learning-rate", type=float, default=2e-4)
    parser.add_argument("--ema-decay", type=float, default=0.999)
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--log-interval", type=int, default=100)
    parser.add_argument("--checkpoint-interval", type=int, default=100)
    parser.add_argument("--resume-from", help="Resume from an earlier .resume.pt checkpoint")
    parser.add_argument("--device", default="cpu")
    args = parser.parse_args()
    if args.log_interval < 1:
        parser.error("--log-interval must be positive")
    try:
        train(args)
    except (ValueError, OSError, RuntimeError, KeyError, TypeError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    main()
