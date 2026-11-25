"""Evaluate a New Star image checkpoint on a separate, permitted held-out folder."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from pathlib import Path

import torch

from data import ImageFolderDataset, audit_image_dataset
from generate import load_checkpoint
from text_conditioning import MAX_TEXT_TOKENS, encode_caption


def fingerprint_dataset(audit: dict) -> str:
    digest = hashlib.sha256()
    digest.update(audit["manifest_sha256"].encode("ascii"))
    for item in audit["images"]:
        digest.update(item["file"].encode("utf-8"))
        digest.update(item["sha256"].encode("ascii"))
    return digest.hexdigest()


def atomic_write_json(payload: dict, destination: str | Path) -> None:
    path = Path(destination)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(
        prefix=path.name + ".", suffix=".tmp", dir=path.parent
    )
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    except Exception:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


@torch.inference_mode()
def evaluate(checkpoint_path: str | Path, data_path: str | Path,
             manifest_path: str | Path, device_name: str = "cpu",
             batch_size: int = 8, seed: int = 2026,
             max_batches: int | None = None) -> dict:
    if not isinstance(batch_size, int) or isinstance(batch_size, bool) or batch_size < 1:
        raise ValueError("batch_size must be a positive integer")
    if not isinstance(seed, int) or isinstance(seed, bool) or not 0 <= seed <= 2**63 - 1:
        raise ValueError("seed must be an integer between 0 and 2^63-1")
    if max_batches is not None and (
        not isinstance(max_batches, int) or isinstance(max_batches, bool) or max_batches < 1
    ):
        raise ValueError("max_batches must be a positive integer when provided")
    device = torch.device(device_name)
    if device.type not in {"cpu", "cuda"}:
        raise ValueError("device must be cpu, cuda, or cuda:N")
    if device.type == "cuda":
        if not torch.cuda.is_available():
            raise ValueError("CUDA requested but unavailable")
        if device.index is not None and device.index >= torch.cuda.device_count():
            raise ValueError("requested CUDA device index is unavailable")

    model, schedule, config = load_checkpoint(checkpoint_path, str(device))
    audit = audit_image_dataset(data_path, manifest_path)
    evaluation_fingerprint = fingerprint_dataset(audit)
    training_fingerprint = config.get("training_dataset_fingerprint")
    if training_fingerprint and evaluation_fingerprint == training_fingerprint:
        raise ValueError("evaluation dataset fingerprint matches the training dataset; provide a separate held-out dataset")
    training_hashes = config.get("training_image_hashes", set())
    evaluation_hashes = {item["sha256"] for item in audit["images"]}
    if training_hashes.intersection(evaluation_hashes):
        raise ValueError("evaluation dataset contains image files used for training; provide non-overlapping held-out images")
    manifest_payload = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    captions = manifest_payload.get("captions") if model.text_vocab_size else None
    if model.text_vocab_size and captions is None:
        raise ValueError("text-conditioned checkpoint evaluation requires captions for every held-out image")
    dataset = ImageFolderDataset(data_path, config["image_size"], captions=captions)
    generator = torch.Generator(device=device).manual_seed(seed)
    squared_error = 0.0
    element_count = 0
    sample_count = 0
    batch_count = 0
    model.eval()

    for start in range(0, len(dataset), batch_size):
        if max_batches is not None and batch_count >= max_batches:
            break
        stop = min(start + batch_size, len(dataset))
        samples = [dataset[index] for index in range(start, stop)]
        if model.text_vocab_size:
            clean = torch.stack([sample[0] for sample in samples]).to(device)
            text_tokens = torch.stack([sample[1] for sample in samples]).to(device)
        else:
            clean = torch.stack(samples).to(device)
            text_tokens = None
        timesteps = torch.randint(
            0, schedule.steps, (clean.shape[0],), device=device, generator=generator
        )
        noise = torch.randn(clean.shape, device=device, generator=generator)
        noisy = schedule.add_noise(clean, timesteps, noise)
        predicted = model(noisy, timesteps, text_tokens=text_tokens)
        if not torch.isfinite(predicted).all():
            raise RuntimeError("image model produced non-finite predictions during evaluation")
        squared_error += float(torch.sum((predicted - noise).square()).item())
        element_count += noise.numel()
        sample_count += clean.shape[0]
        batch_count += 1

    if element_count == 0:
        raise ValueError("evaluation dataset produced no samples")
    return {
        "format": "new-star-image-evaluation-v1",
        "checkpoint": str(Path(checkpoint_path)),
        "model": "new-star-ddpm-text-conditioned" if model.text_vocab_size else "new-star-ddpm-unconditional",
        "weights": config["weights_source"],
        "image_size": config["image_size"],
        "diffusion_steps": config["diffusion_steps"],
        "evaluation_dataset": {
            "dataset_name": audit["dataset_name"],
            "image_count": audit["image_count"],
            "fingerprint": evaluation_fingerprint,
            "manifest_sha256": audit["manifest_sha256"],
        },
        "metrics": {
            "noise_prediction_mse": squared_error / element_count,
            "samples_evaluated": sample_count,
            "batches_evaluated": batch_count,
            "elements_evaluated": element_count,
        },
        "seed": seed,
        "device": str(device),
        "note": "Noise-prediction MSE is a denoising diagnostic, not a perceptual image-quality score.",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--data", required=True, help="Separate held-out image folder")
    parser.add_argument("--manifest", required=True, help="Held-out dataset permission manifest")
    parser.add_argument("--output", default="artifacts/newstar-image-evaluation.json")
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--seed", type=int, default=2026)
    parser.add_argument("--max-batches", type=int)
    parser.add_argument("--device", default="cpu")
    args = parser.parse_args()
    try:
        report = evaluate(
            args.checkpoint, args.data, args.manifest, args.device,
            args.batch_size, args.seed, args.max_batches,
        )
        atomic_write_json(report, args.output)
        print(json.dumps(report, ensure_ascii=False, allow_nan=False))
    except (ValueError, OSError, RuntimeError, KeyError, TypeError) as exc:
        parser.error(str(exc))


if __name__ == "__main__":
    main()
