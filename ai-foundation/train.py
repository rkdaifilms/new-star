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
from bpe_tokenizer import ByteBPETokenizer
from hardware import autocast_context, create_grad_scaler, hardware_report, resolve_device, resolve_precision
from artifact_io import atomic_torch_save, atomic_write_json


def sample_balanced_batch(corpora: dict[str, torch.Tensor], batch_size: int,
                          block_size: int, device: str, generator=None):
    """Sample each row from a uniformly selected language corpus."""
    if not isinstance(batch_size, int) or isinstance(batch_size, bool) or batch_size < 1:
        raise ValueError("batch_size must be a positive integer")
    if not isinstance(block_size, int) or isinstance(block_size, bool) or block_size < 1:
        raise ValueError("block_size must be a positive integer")
    if not isinstance(corpora, dict) or not corpora:
        raise ValueError("at least one language corpus is required")
    if generator is not None and (
        not isinstance(generator, torch.Generator) or generator.device.type != "cpu"
    ):
        raise ValueError("generator must be a CPU torch.Generator or None")
    try:
        target_device = torch.device(device)
    except (TypeError, RuntimeError) as exc:
        raise ValueError("device must identify a valid PyTorch device") from exc
    languages = sorted(corpora)
    for language in languages:
        data = corpora[language]
        if not isinstance(language, str) or not language.strip():
            raise ValueError("language corpus keys must be non-empty strings")
        if not isinstance(data, torch.Tensor) or data.ndim != 1 or data.dtype != torch.long:
            raise ValueError(f"language {language!r} corpus must be a one-dimensional torch.long tensor")
        if data.numel() < block_size + 1:
            raise ValueError(f"language {language!r} has too few tokens")
    rows_x, rows_y = [], []
    for _ in range(batch_size):
        language_index = int(torch.randint(len(languages), (1,), generator=generator).item())
        data = corpora[languages[language_index]]
        start = int(torch.randint(0, data.numel() - block_size, (1,), generator=generator).item())
        rows_x.append(data[start:start + block_size])
        rows_y.append(data[start + 1:start + block_size + 1])
    return torch.stack(rows_x).to(target_device), torch.stack(rows_y).to(target_device)


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
    parser.add_argument("--tokenizer", choices=("char", "bpe"), default="char", help="Tokenizer for new checkpoints; char preserves legacy behavior")
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--resume-from", help="Resume model/optimizer/RNG state from a .resume.pt checkpoint")
    parser.add_argument("--device", default="auto", help="auto, cpu, cuda, or cuda:N")
    parser.add_argument("--precision", choices=("auto", "fp32", "fp16", "bf16"), default="auto")
    parser.add_argument("--gradient-checkpointing", action="store_true", help="Trade extra compute for lower activation memory")
    args = parser.parse_args()
    if args.steps < 1 or args.batch_size < 1 or args.block_size < 1:
        parser.error("--steps, --batch-size, and --block-size must be positive")
    if args.max_vocab_size < 256:
        parser.error("--max-vocab-size must be at least 256 (UTF-8 fallback vocabulary)")
    try:
        device = resolve_device(args.device)
        precision = resolve_precision(device, args.precision)
    except (ValueError, RuntimeError) as exc:
        parser.error(str(exc))
    scaler = create_grad_scaler(device, precision)
    runtime_info = hardware_report(device, precision)
    print("Training hardware: " + json.dumps(runtime_info, ensure_ascii=False), flush=True)

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
    training_text = combine_languages(train_by_language)
    tokenizer = (
        ByteBPETokenizer.train(training_text, max_merges=args.max_vocab_size - 256)
        if args.tokenizer == "bpe"
        else UnicodeCharTokenizer.train(training_text, args.max_vocab_size)
    )
    train_data = {lang: torch.tensor(tokenizer.encode(text), dtype=torch.long)
                  for lang, text in train_by_language.items()}
    validation_data = {lang: torch.tensor(tokenizer.encode(text), dtype=torch.long)
                       for lang, text in validation_by_language.items()}

    config = NewStarConfig(vocab_size=len(tokenizer), block_size=args.block_size,
                           width=args.width, layers=args.layers, heads=args.heads,
                           gradient_checkpointing=args.gradient_checkpointing)
    model = NewStarGPT(config).to(device)
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
        "tokenizer_type": args.tokenizer,
        "precision": precision,
        "gradient_checkpointing": args.gradient_checkpointing,
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
            resume = torch.load(resume_path, map_location=device, weights_only=True)
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
                                     device, batch_generator)
        with autocast_context(device, precision):
            _, loss = model(x, y)
        optimizer.zero_grad(set_to_none=True)
        scaler.scale(loss).backward()
        scaler.unscale_(optimizer)
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        scaler.step(optimizer)
        scaler.update()
        if step == 1 or step % 100 == 0 or step == args.steps:
            model.eval()
            val_losses = []
            with torch.no_grad():
                for language in sorted(validation_data):
                    vx, vy = sample_balanced_batch(
                        {language: validation_data[language]}, args.batch_size,
                        args.block_size, device, batch_generator,
                    )
                    with autocast_context(device, precision):
                        _, val_loss = model(vx, vy)
                    val_losses.append(float(val_loss.item()))
            final_validation_loss = sum(val_losses) / len(val_losses)
            resume_path = Path(args.output).with_suffix(Path(args.output).suffix + ".resume.pt")
            atomic_torch_save({
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
            }, resume_path)
            print(f"step={step} train_loss={loss.item():.4f} "
                  f"balanced_val_loss={final_validation_loss:.4f}", flush=True)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    checkpoint_format = ("new-star-byte-bpe-gpt-v1" if args.tokenizer == "bpe"
                         else "new-star-unicode-char-gpt-v1")
    atomic_torch_save({"format": checkpoint_format, "config": vars(config),
                       "tokenizer": tokenizer.to_dict(), "state_dict": model.cpu().state_dict()}, output)
    metadata = {
        "format": checkpoint_format,
        "config": vars(config),
        "tokenizer_type": tokenizer.to_dict()["type"],
        "training_steps": args.steps,
        "resumed_from_step": start_step,
        "final_validation_loss": final_validation_loss,
        "learning_rate": args.learning_rate,
        "batch_size": args.batch_size,
        "block_size": args.block_size,
        "vocabulary_size": len(tokenizer),
        "seed": args.seed,
        "data_fingerprint": data_fingerprint,
        "tokenizer_fingerprint": tokenizer_fingerprint,
        "evaluation_protocol": "pretraining-balanced-language-validation-ce-v1",
        "validation_metric": "unweighted macro-average of per-language token-weighted next-token cross-entropy",
        "languages": sorted(train_by_language),
        "training_characters_by_language": {lang: len(text) for lang, text in train_by_language.items()},
        "validation_characters_by_language": {lang: len(text) for lang, text in validation_by_language.items()},
        "sampling": "uniform language selection per batch row",
        "hardware": runtime_info,
    }
    if audit_report is not None:
        atomic_write_json(audit_report, output.with_suffix(output.suffix + ".corpus-audit.json"))
    atomic_write_json(metadata, output.with_suffix(output.suffix + ".json"))
    print(f"Saved New Star checkpoint: {output}")


if __name__ == "__main__":
    main()
