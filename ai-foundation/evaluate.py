"""Evaluate New Star checkpoints on a held-out UTF-8 corpus."""
from __future__ import annotations

import argparse
import math
from pathlib import Path

import torch

from model import NewStarConfig, NewStarGPT
from tokenizer import UnicodeCharTokenizer


def evaluate_model(model: NewStarGPT, data: torch.Tensor, batch_size: int = 16,
                   batches: int = 20, device: str = "cpu", seed: int = 1234) -> float:
    """Return mean next-token cross-entropy over deterministic random windows."""
    block_size = model.config.block_size
    if data.ndim != 1 or data.dtype != torch.long:
        raise ValueError("evaluation data must be a one-dimensional torch.long tensor")
    if len(data) <= block_size + 1:
        raise ValueError("evaluation corpus must contain more tokens than block_size + 1")
    if batch_size < 1 or batches < 1:
        raise ValueError("batch_size and batches must be positive")
    generator = torch.Generator(device="cpu").manual_seed(seed)
    model.eval()
    losses = []
    with torch.inference_mode():
        for _ in range(batches):
            starts = torch.randint(0, len(data) - block_size - 1,
                                   (batch_size,), generator=generator)
            x = torch.stack([data[i:i + block_size] for i in starts]).to(device)
            y = torch.stack([data[i + 1:i + block_size + 1] for i in starts]).to(device)
            _, loss = model(x, y)
            losses.append(float(loss.item()))
    return sum(losses) / len(losses)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--data", required=True, help="Held-out UTF-8 text corpus")
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--batches", type=int, default=50)
    parser.add_argument("--seed", type=int, default=1234)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    if args.batch_size < 1 or args.batches < 1:
        parser.error("--batch-size and --batches must be positive")

    checkpoint = torch.load(args.checkpoint, map_location=args.device, weights_only=True)
    checkpoint_format = checkpoint.get("format")
    if checkpoint_format not in {"new-star-byte-gpt-v1", "new-star-unicode-char-gpt-v1"}:
        parser.error("Unsupported checkpoint format")
    config = NewStarConfig(**checkpoint["config"])
    model = NewStarGPT(config).to(args.device)
    model.load_state_dict(checkpoint["state_dict"])

    corpus = Path(args.data).read_text(encoding="utf-8")
    if checkpoint_format == "new-star-unicode-char-gpt-v1":
        tokenizer = UnicodeCharTokenizer.from_dict(checkpoint["tokenizer"])
        encoded = tokenizer.encode(corpus)
        unit = "nats/token"
    else:
        encoded = list(corpus.encode("utf-8"))
        unit = "nats/byte"
    data = torch.tensor(encoded, dtype=torch.long)
    try:
        loss = evaluate_model(model, data, args.batch_size, args.batches,
                              args.device, args.seed)
    except ValueError as exc:
        parser.error(str(exc))
    perplexity = math.exp(min(loss, 80.0))
    print(f"heldout_cross_entropy={loss:.4f} {unit}")
    print(f"heldout_perplexity={perplexity:.4f}")


if __name__ == "__main__":
    main()
