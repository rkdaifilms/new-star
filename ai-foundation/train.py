"""Train New Star's small Unicode character decoder model on a local text corpus."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch

from model import NewStarConfig, NewStarGPT
from tokenizer import UnicodeCharTokenizer


def batches(data: torch.Tensor, batch_size: int, block_size: int, device: str):
    if len(data) <= block_size + 1:
        raise ValueError("training corpus must contain more tokens than block_size + 1")
    starts = torch.randint(0, len(data) - block_size - 1, (batch_size,))
    x = torch.stack([data[i:i + block_size] for i in starts]).to(device)
    y = torch.stack([data[i + 1:i + block_size + 1] for i in starts]).to(device)
    return x, y


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True, help="Path to a UTF-8 text corpus you are allowed to use")
    parser.add_argument("--output", default="artifacts/newstar-small.pt")
    parser.add_argument("--steps", type=int, default=2000)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--block-size", type=int, default=128)
    parser.add_argument("--width", type=int, default=128)
    parser.add_argument("--layers", type=int, default=4)
    parser.add_argument("--heads", type=int, default=4)
    parser.add_argument("--learning-rate", type=float, default=3e-4)
    parser.add_argument("--max-vocab-size", type=int, default=8192)
    parser.add_argument("--seed", type=int, default=1337, help="Random seed for reproducible initialization and sampling")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    if args.steps < 1 or args.batch_size < 1:
        parser.error("--steps and --batch-size must be positive")
    if args.max_vocab_size < 256:
        parser.error("--max-vocab-size must be at least 256 (UTF-8 fallback vocabulary)")

    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)

    corpus = Path(args.data).read_text(encoding="utf-8")
    split = max(args.block_size + 2, int(len(corpus) * 0.9))
    if split >= len(corpus):
        parser.error("corpus is too small for the requested block size")
    train_text, validation_text = corpus[:split], corpus[split:]
    tokenizer = UnicodeCharTokenizer.train(train_text, args.max_vocab_size)
    train_data = torch.tensor(tokenizer.encode(train_text), dtype=torch.long)
    validation_data = torch.tensor(tokenizer.encode(validation_text), dtype=torch.long)
    if len(validation_data) <= args.block_size + 1:
        validation_data = train_data

    config = NewStarConfig(vocab_size=len(tokenizer), block_size=args.block_size,
                           width=args.width, layers=args.layers, heads=args.heads)
    model = NewStarGPT(config).to(args.device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate)
    for step in range(1, args.steps + 1):
        model.train()
        x, y = batches(train_data, args.batch_size, args.block_size, args.device)
        _, loss = model(x, y)
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        if step == 1 or step % 100 == 0 or step == args.steps:
            model.eval()
            with torch.no_grad():
                vx, vy = batches(validation_data, args.batch_size, args.block_size, args.device)
                _, val_loss = model(vx, vy)
            print(f"step={step} train_loss={loss.item():.4f} val_loss={val_loss.item():.4f}", flush=True)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"format": "new-star-unicode-char-gpt-v1", "config": vars(config),
                "tokenizer": tokenizer.to_dict(), "state_dict": model.cpu().state_dict()}, output)
    output.with_suffix(output.suffix + ".json").write_text(
        json.dumps({"format": "new-star-unicode-char-gpt-v1", "config": vars(config),
                    "tokenizer_type": "unicode-char-byte-fallback-v1",
                    "training_steps": args.steps, "corpus_characters": len(corpus),
                    "vocabulary_size": len(tokenizer), "seed": args.seed}, indent=2),
        encoding="utf-8",
    )
    print(f"Saved New Star checkpoint: {output}")


if __name__ == "__main__":
    main()
