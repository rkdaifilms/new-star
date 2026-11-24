"""Train New Star's experimental multilingual decoder model on permitted local text."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import torch

from audit_corpus import audit_corpus
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
    parser.add_argument("--manifest", help="required provenance JSON for multilingual JSONL data")
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
    parser.add_argument("--resume-from", help="Resume model/optimizer/RNG state from a .resume.pt checkpoint")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    if args.steps < 1 or args.batch_size < 1 or args.block_size < 1:
        parser.error("--steps, --batch-size, and --block-size must be positive")
    if args.max_vocab_size < 256:
        parser.error("--max-vocab-size must be at least 256 (UTF-8 fallback vocabulary)")

    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)

    audit_report = None
    if args.data_format == "jsonl":
        if not args.manifest:
            parser.error("--manifest is required with --data-format jsonl")
        try:
            audit_report = audit_corpus(
                args.data, args.manifest, args.block_size + 2, args.block_size + 2
            )
            if not audit_report["ok"]:
                parser.error("corpus audit failed: " + "; ".join(audit_report["errors"]))
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
    training_config = {
        "seed": args.seed,
        "data_format": args.data_format,
        "learning_rate": args.learning_rate,
        "batch_size": args.batch_size,
        "block_size": args.block_size,
        "width": args.width,
        "layers": args.layers,
        "heads": args.heads,
        "max_vocab_size": args.max_vocab_size,
    }
    data_fingerprint = hashlib.sha256(Path(args.data).read_bytes()).hexdigest()
    tokenizer_fingerprint = hashlib.sha256(json.dumps(
        tokenizer.to_dict(), ensure_ascii=False, sort_keys=True
    ).encode("utf-8")).hexdigest()
    start_step = 0
    if args.resume_from:
        resume_path = Path(args.resume_from)
        if not resume_path.is_file():
            parser.error(f"resume checkpoint does not exist: {resume_path}")
        try:
            resume = torch.load(resume_path, map_location=args.device, weights_only=True)
        except (OSError, RuntimeError, ValueError) as exc:
            parser.error(f"could not load resume checkpoint: {exc}")
        if resume.get("format") != "new-star-pretrain-resume-v1":
            parser.error("unsupported pretraining resume checkpoint format")
        if resume.get("data_fingerprint") != data_fingerprint:
            parser.error("resume data does not match the original training dataset")
        if resume.get("tokenizer_fingerprint") != tokenizer_fingerprint:
            parser.error("resume tokenizer does not match the original training dataset")
        if resume.get("training_config") != training_config:
            parser.error("resume training configuration must match the original run")
        start_step = int(resume.get("step", 0))
        if start_step < 0 or start_step >= args.steps:
            parser.error("--steps must be greater than the saved resume step")
        model.load_state_dict(resume["model_state"])
        optimizer.load_state_dict(resume["optimizer_state"])
        batch_generator.set_state(resume["batch_generator_state"])
        torch.set_rng_state(resume["torch_rng_state"].cpu())
        if torch.cuda.is_available() and resume.get("cuda_rng_states") is not None:
            torch.cuda.set_rng_state_all(
                [state.cpu() for state in resume["cuda_rng_states"]]
            )

    final_validation_loss = None
    for step in range(start_step + 1, args.steps + 1):
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
            final_validation_loss = sum(val_losses) / len(val_losses)
            resume_path = Path(args.output).with_suffix(Path(args.output).suffix + ".resume.pt")
            resume_path.parent.mkdir(parents=True, exist_ok=True)
            temporary = resume_path.with_name(resume_path.name + ".tmp")
            torch.save({
                "format": "new-star-pretrain-resume-v1",
                "step": step,
                "model_state": model.state_dict(),
                "optimizer_state": optimizer.state_dict(),
                "batch_generator_state": batch_generator.get_state(),
                "torch_rng_state": torch.get_rng_state(),
                "cuda_rng_states": torch.cuda.get_rng_state_all() if torch.cuda.is_available() else None,
                "seed": args.seed,
                "data_fingerprint": data_fingerprint,
                "tokenizer_fingerprint": tokenizer_fingerprint,
                "training_config": training_config,
            }, temporary)
            temporary.replace(resume_path)
            print(f"step={step} train_loss={loss.item():.4f} "
                  f"balanced_val_loss={final_validation_loss:.4f}", flush=True)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"format": "new-star-unicode-char-gpt-v1", "config": vars(config),
                "tokenizer": tokenizer.to_dict(), "state_dict": model.cpu().state_dict()}, output)
    metadata = {
        "format": "new-star-unicode-char-gpt-v1",
        "config": vars(config),
        "tokenizer_type": "unicode-char-byte-fallback-v1",
        "training_steps": args.steps,
        "resumed_from_step": start_step,
        "final_validation_loss": final_validation_loss,
        "learning_rate": args.learning_rate,
        "batch_size": args.batch_size,
        "block_size": args.block_size,
        "vocabulary_size": len(tokenizer),
        "seed": args.seed,
        "languages": sorted(train_by_language),
        "training_characters_by_language": {lang: len(text) for lang, text in train_by_language.items()},
        "validation_characters_by_language": {lang: len(text) for lang, text in validation_by_language.items()},
        "sampling": "uniform language selection per batch row",
    }
    if audit_report is not None:
        output.with_suffix(output.suffix + ".corpus-audit.json").write_text(
            json.dumps(audit_report, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    output.with_suffix(output.suffix + ".json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"Saved New Star checkpoint: {output}")


if __name__ == "__main__":
    main()
