"""Generate images from a trained New Star unconditional DDPM checkpoint."""
from __future__ import annotations

import argparse
import pickle
from pathlib import Path

import torch
from PIL import Image

from model import DDPMSchedule, NewStarImageUNet


def load_checkpoint(path: str | Path, device: str):
    try:
        checkpoint = torch.load(path, map_location=device, weights_only=True)
    except (OSError, RuntimeError, TypeError, ValueError, pickle.UnpicklingError) as exc:
        raise ValueError("could not safely load New Star image checkpoint") from exc
    if not isinstance(checkpoint, dict) or checkpoint.get("format") != "new-star-ddpm-image-v1":
        raise ValueError("unsupported New Star image checkpoint format")
    raw_config = checkpoint.get("model_config")
    raw_state = checkpoint.get("state_dict")
    if not isinstance(raw_config, dict):
        raise ValueError("image checkpoint is missing a valid model_config")
    required = ("base_channels", "image_size", "diffusion_steps")
    if any(key not in raw_config for key in required):
        raise ValueError("image checkpoint model_config is incomplete")
    for key in required:
        value = raw_config[key]
        if not isinstance(value, int) or isinstance(value, bool):
            raise ValueError(f"image checkpoint {key} must be an integer")
    if raw_config["base_channels"] < 8:
        raise ValueError("image checkpoint base_channels must be at least 8")
    if raw_config["image_size"] not in {32, 64}:
        raise ValueError("image checkpoint image_size must be 32 or 64 pixels")
    if raw_config["diffusion_steps"] < 2:
        raise ValueError("image checkpoint diffusion_steps must be at least 2")
    if not isinstance(raw_state, dict):
        raise ValueError("image checkpoint is missing a model state dictionary")
    try:
        model = NewStarImageUNet(raw_config["base_channels"]).to(device)
    except (TypeError, ValueError) as exc:
        raise ValueError("image checkpoint model configuration is invalid") from exc
    selected_weights = checkpoint.get("ema_state_dict", raw_state)
    if not isinstance(selected_weights, dict):
        raise ValueError("image checkpoint EMA state must be a state dictionary")
    expected_state = model.state_dict()
    if selected_weights.keys() != expected_state.keys():
        raise ValueError("image checkpoint weights do not match the model architecture")
    for key, expected in expected_state.items():
        actual = selected_weights[key]
        if not isinstance(actual, torch.Tensor):
            raise ValueError(f"image checkpoint weight {key!r} is not a tensor")
        if actual.shape != expected.shape or actual.dtype != expected.dtype:
            raise ValueError(f"image checkpoint weight {key!r} has an incompatible shape or dtype")
        if torch.is_floating_point(actual) and not torch.isfinite(actual).all():
            raise ValueError(f"image checkpoint weight {key!r} contains non-finite values")
    try:
        model.load_state_dict(selected_weights, strict=True)
    except (RuntimeError, TypeError) as exc:
        raise ValueError("image checkpoint weights could not be loaded strictly") from exc
    config = dict(raw_config)
    config["weights_source"] = "ema" if "ema_state_dict" in checkpoint else "raw"
    config["training_dataset_fingerprint"] = checkpoint.get("dataset_fingerprint")
    training_audit = checkpoint.get("dataset_audit", {})
    if not isinstance(training_audit, dict):
        raise ValueError("image checkpoint dataset audit must be an object")
    training_images = training_audit.get("images", [])
    if not isinstance(training_images, list):
        raise ValueError("image checkpoint dataset audit images must be a list")
    hashes = set()
    for item in training_images:
        if not isinstance(item, dict) or not isinstance(item.get("sha256"), str):
            raise ValueError("image checkpoint contains an invalid training image audit entry")
        digest = item["sha256"]
        if len(digest) != 64 or any(character not in "0123456789abcdef" for character in digest):
            raise ValueError("image checkpoint contains an invalid training image SHA-256")
        hashes.add(digest)
    config["training_image_hashes"] = hashes
    model.eval()
    schedule = DDPMSchedule(config["diffusion_steps"]).to(device)
    return model, schedule, config


def save_image_grid(images: torch.Tensor, destination: str | Path) -> None:
    if images.ndim != 4 or images.shape[1] != 3:
        raise ValueError("images must have shape [batch, 3, height, width]")
    count, _, height, width = images.shape
    columns = min(4, count)
    rows = (count + columns - 1) // columns
    canvas = Image.new("RGB", (columns * width, rows * height))
    for index, tensor in enumerate(images):
        pixels = tensor.detach().cpu().clamp(-1, 1).add(1).mul(127.5).byte()
        image = Image.frombytes(
            "RGB", (width, height),
            bytes(pixels.permute(1, 2, 0).contiguous().view(-1).tolist()),
        )
        canvas.paste(image, ((index % columns) * width, (index // columns) * height))
    output = Path(destination)
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output, format="PNG")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--output", default="artifacts/newstar-generated.png")
    parser.add_argument("--count", type=int, default=4)
    parser.add_argument("--seed", type=int, default=2026)
    parser.add_argument("--device", default="cpu")
    args = parser.parse_args()
    if not 1 <= args.count <= 16:
        parser.error("--count must be from 1 to 16")
    if not 0 <= args.seed <= 2**63 - 1:
        parser.error("--seed must be between 0 and 2^63-1")
    model, schedule, config = load_checkpoint(args.checkpoint, args.device)
    images = schedule.sample(
        model, args.count, config["image_size"], args.device, seed=args.seed
    )
    save_image_grid(images, args.output)
    print(f"Saved {args.count} unconditional image(s) to {args.output}")


if __name__ == "__main__":
    main()
