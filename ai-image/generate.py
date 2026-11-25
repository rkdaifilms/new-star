"""Generate images from a trained New Star unconditional DDPM checkpoint."""
from __future__ import annotations

import argparse
from pathlib import Path

import torch
from PIL import Image

from model import DDPMSchedule, NewStarImageUNet


def load_checkpoint(path: str | Path, device: str):
    checkpoint = torch.load(path, map_location=device, weights_only=True)
    if checkpoint.get("format") != "new-star-ddpm-image-v1":
        raise ValueError("unsupported New Star image checkpoint format")
    config = checkpoint["model_config"]
    model = NewStarImageUNet(config["base_channels"]).to(device)
    model.load_state_dict(checkpoint["state_dict"])
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
