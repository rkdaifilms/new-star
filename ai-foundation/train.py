"""Train New Star's experimental multilingual decoder model on permitted local text."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch

from corpus import combine_languages, load_language_corpora
from model import NewStarConfig, NewStarGPT
from tokenizer import UnicodeCharTokenizer


def sample_balanced_batch(corpora: dict[str, torch.Tensor], batch_size: int,
                          block_size: int, device: str, generator=None):
    """Sample each row from a uniformly selected language corpus."""
    languages = sorted(corpora)
    if not languages:
        raise ValueError("at least one language corpus is required")
    rows_x, rows_y = [], []
    for _ in range(batch_size):
        language_index = int(torch.randint(len(languages), (1,), generator=generator).item())
        data = corpora[languages[language_index]]
        if len(data) <= block_size + 1:
            raise ValueError(f"language {languages[language_index]!r} has too few tokens")
        start = int(torch.randint(0, len(data) - block_size - 1, (1,), generator=generator).item())
        rows_x.append(data[start:start + block_size])
        rows_y.append(data[start + 1:start + block_size + 1])
    return torch.stack(rows_x).to(device), torch.stack(rows_y).to(device)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True, help="Permitted UTF-8 text file or language-tagged JSONL corpus")
    parser.add_argument("--data-format", choices=("text", "jsonl"), default="text")
    parser.add_argument("--output", default="artifacts/newstar-small.pt")
    parser.add_argument("--steps", type=int, default=2000)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--block-size", type=int, default=128)
    parser.add_argument("--width", type=int, default=128)
    parser.add_argument("--layers", type=int, default=4)
    parser.add_argument("--heads", type=int, default=4)
    parser.add_argument("--learning-rate", type=float, default=3e-4)
    parser.add_argument("--max-vocab-size", type=int, default=8192)
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    if args.steps < 1 or args.batch_size < 1 or args.block_size < 1:
        parser.error("--steps, --batch-size, and --block-size must be positive")
    if args.max_vocab_size < 256:
        parser.error("--max-vocab-size must be at least 256 (UTF-8 fallback vocabulary)")

    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)

    if args.data_format == "jsonl":
        try:
            train_by_language, validation_by_language = load_language_corpora(args.data)
        except ValueError as exc:
            parser.error(str(exc))
    else:
        corpus = Path(args.data).read_text(encoding="utf-8")
        split = int(len(corpus) * 0.9)
        if split <= args.block_size + 1 or len(corpus) - split <= args.block_size + 1:
            parser.error("text corpus must provide separate train and validation portions larger than block-size")
        train_by_language = {"und": corpus[:split]}
        validation_by_language = {"und": corpus[split:]}

    for language, text in train_by_language.items():
        if len(text) <= args.block_size + 1 or len(validation_by_language[language]) <= args.block_size + 1:
            parser.error(f"language {language!r} needs enough train and validation text for block-size")
    tokenizer = UnicodeCharTokenizer.train(combine_languages(train_by_language), args.max_vocab_size)
    train_data = {lang: torch.tensor(tokenizer.encode(text), dtype=torch.long)
                  for lang, text in train_by_language.items()}
    validation_data = {lang: torch.tensor(tokenizer.encode(text), dtype=torch.long)
                       for lang, text in validation_by_language.items()}

    config = NewStarConfig(vocab_size=len(tokenizer), block_size=args.block_size,
                           width=args.width, layers=args.layers, heads=args.heads)
    model = NewStarGPT(config).to(args.device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate)
    batch_generator = torch.Generator(device="cpu").manual_seed(args.seed + 1)
    for step in range(1, args.steps + 1):
        model.train()
        x, y = sample_balanced_batch(train_data, args.batch_size, args.block_size,
                                     args.device, batch_generator)
        _, loss = model(x, y)
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        if step == 1 or step % 100 == 0 or step == args.steps:
            model.eval()
            val_losses = []
            with torch.no_grad():
                for language in sorted(validation_data):
                    vx, vy = sample_balanced_batch(
                        {language: validation_data[language]}, args.batch_size,
                        args.block_size, args.device, batch_generator,
                    )
                    _, val_loss = model(vx, vy)
                    val_losses.append(float(val_loss.item()))
            print(f"step={step} train_loss={loss.item():.4f} "
                  f"balanced_val_loss={sum(val_losses) / len(val_losses):.4f}", flush=True)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"format": "new-star-unicode-char-gpt-v1", "config": vars(config),
                "tokenizer": tokenizer.to_dict(), "state_dict": model.cpu().state_dict()}, output)
    metadata = {
        "format": "new-star-unicode-char-gpt-v1",
        "config": vars(config),
        "tokenizer_type": "unicode-char-byte-fallback-v1",
        "training_steps": args.steps,
        "vocabulary_size": len(tokenizer),
        "seed": args.seed,
        "languages": sorted(train_by_language),
        "training_characters_by_language": {lang: len(text) for lang, text in train_by_language.items()},
        "validation_characters_by_language": {lang: len(text) for lang, text in validation_by_language.items()},
        "sampling": "uniform language selection per batch row",
    }
    output.with_suffix(output.suffix + ".json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"Saved New Star checkpoint: {output}")


if __name__ == "__main__":
    main()
