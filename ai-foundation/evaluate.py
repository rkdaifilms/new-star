"""Evaluate New Star checkpoints on a held-out UTF-8 corpus."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

import torch

from model import NewStarConfig, NewStarGPT
from tokenizer import UnicodeCharTokenizer
from corpus import load_language_validation_corpora


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


def evaluate_full_corpus(model: NewStarGPT, data: torch.Tensor,
                          batch_size: int = 16, device: str = "cpu") -> tuple[float, int]:
    """Compute exact token-weighted cross-entropy over every next-token target."""
    block_size = model.config.block_size
    if data.ndim != 1 or data.dtype != torch.long:
        raise ValueError("evaluation data must be a one-dimensional torch.long tensor")
    if len(data) <= 1:
        raise ValueError("evaluation corpus must contain at least two tokens")
    if batch_size < 1:
        raise ValueError("batch_size must be positive")

    full_windows = (len(data) - 1) // block_size
    total_loss = 0.0
    total_tokens = 0
    model.eval()
    with torch.inference_mode():
        for batch_start in range(0, full_windows, batch_size):
            batch_end = min(batch_start + batch_size, full_windows)
            starts = range(batch_start * block_size, batch_end * block_size, block_size)
            x = torch.stack([data[start:start + block_size] for start in starts]).to(device)
            y = torch.stack([data[start + 1:start + block_size + 1] for start in starts]).to(device)
            _, loss = model(x, y)
            count = x.numel()
            total_loss += float(loss.item()) * count
            total_tokens += count

        remainder_start = full_windows * block_size
        if remainder_start < len(data) - 1:
            x = data[remainder_start:len(data) - 1].unsqueeze(0).to(device)
            y = data[remainder_start + 1:].unsqueeze(0).to(device)
            _, loss = model(x, y)
            count = x.numel()
            total_loss += float(loss.item()) * count
            total_tokens += count

    return total_loss / total_tokens, total_tokens


def evaluate_language_corpora(model, validation_by_language, tokenizer=None,
                              batch_size=16, batches=50, device="cpu", seed=1234):
    """Return reproducible per-language held-out metrics and macro aggregates."""
    if not validation_by_language:
        raise ValueError("at least one validation language is required")
    metrics = {}
    for language in sorted(validation_by_language):
        text = validation_by_language[language]
        if not isinstance(text, str) or not text.strip():
            raise ValueError(f"validation corpus for {language!r} is empty")
        encoded = tokenizer.encode(text) if tokenizer else list(text.encode("utf-8"))
        data = torch.tensor(encoded, dtype=torch.long)
        loss, evaluated_tokens = evaluate_full_corpus(model, data, batch_size, device)
        metrics[language] = {
            "cross_entropy": loss,
            "perplexity": math.exp(min(loss, 80.0)),
            "evaluated_tokens": evaluated_tokens,
        }
    macro_loss = sum(item["cross_entropy"] for item in metrics.values()) / len(metrics)
    return {
        "languages": metrics,
        "macro_average_cross_entropy": macro_loss,
        "macro_geometric_mean_perplexity": math.exp(min(macro_loss, 80.0)),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--data", required=True, help="Held-out UTF-8 text corpus or language-tagged JSONL")
    parser.add_argument("--data-format", choices=("text", "jsonl"), default="text")
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--batches", type=int, default=50, help="Deprecated compatibility option; evaluation now covers the full corpus")
    parser.add_argument("--seed", type=int, default=1234)
    parser.add_argument("--report-json", help="Optional path to save machine-readable evaluation metrics")
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

    if checkpoint_format == "new-star-unicode-char-gpt-v1":
        tokenizer = UnicodeCharTokenizer.from_dict(checkpoint["tokenizer"])
        unit = "nats/token"
    else:
        tokenizer = None
        unit = "nats/byte"

    checkpoint_path = Path(args.checkpoint)
    data_path = Path(args.data)
    tokenizer_payload = (
        json.dumps(tokenizer.to_dict(), ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        if tokenizer is not None else b"utf8-byte-tokenizer-v1"
    )
    report_metadata = {
        "report_format": "new-star-evaluation-report-v1",
        "checkpoint": checkpoint_path.name,
        "checkpoint_sha256": hashlib.sha256(checkpoint_path.read_bytes()).hexdigest(),
        "checkpoint_format": checkpoint_format,
        "data": data_path.name,
        "data_sha256": hashlib.sha256(data_path.read_bytes()).hexdigest(),
        "tokenizer_sha256": hashlib.sha256(tokenizer_payload).hexdigest(),
        "model_config": vars(config),
        "metric_unit": unit,
        "evaluation_method": "full-corpus-token-weighted",
        "batch_size": args.batch_size,
    }

    if args.data_format == "jsonl":
        try:
            validation_by_language = load_language_validation_corpora(args.data)
            report = evaluate_language_corpora(
                model, validation_by_language, tokenizer, args.batch_size,
                args.batches, args.device, args.seed,
            )
        except ValueError as exc:
            parser.error(str(exc))
        for language, metrics in report["languages"].items():
            print(f"language={language} heldout_cross_entropy={metrics['cross_entropy']:.4f} {unit} "
                  f"perplexity={metrics['perplexity']:.4f}")
        macro_loss = report["macro_average_cross_entropy"]
        print(f"macro_average_cross_entropy={macro_loss:.4f} {unit}")
        print(f"macro_geometric_mean_perplexity={report['macro_geometric_mean_perplexity']:.4f}")
        if args.report_json:
            output = {
                **report_metadata,
                "seed": args.seed,
                **report,
            }
            report_path = Path(args.report_json)
            report_path.parent.mkdir(parents=True, exist_ok=True)
            report_path.write_text(
                json.dumps(output, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
    else:
        corpus = Path(args.data).read_text(encoding="utf-8")
        encoded = tokenizer.encode(corpus) if tokenizer else list(corpus.encode("utf-8"))
        data = torch.tensor(encoded, dtype=torch.long)
        try:
            loss, evaluated_tokens = evaluate_full_corpus(
                model, data, args.batch_size, args.device
            )
        except ValueError as exc:
            parser.error(str(exc))
        perplexity = math.exp(min(loss, 80.0))
        print(f"heldout_cross_entropy={loss:.4f} {unit}")
        print(f"heldout_perplexity={perplexity:.4f}")
        print(f"evaluated_tokens={evaluated_tokens}")
        if args.report_json:
            output = {
                **report_metadata,
                "heldout_cross_entropy": loss,
                "heldout_perplexity": perplexity,
                "evaluated_tokens": evaluated_tokens,
            }
            report_path = Path(args.report_json)
            report_path.parent.mkdir(parents=True, exist_ok=True)
            report_path.write_text(
                json.dumps(output, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )


if __name__ == "__main__":
    main()
