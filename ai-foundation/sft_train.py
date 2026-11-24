"""Supervised fine-tuning for New Star using response-only masked chat targets."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import torch

from audit_corpus import audit_corpus
from instruction_data import encode_instruction_example, load_instruction_corpora
from model import NewStarConfig, NewStarGPT
from tokenizer import UnicodeCharTokenizer, tokenizer_from_dict
from bpe_tokenizer import ByteBPETokenizer


def encode_corpora(corpora, tokenizer, block_size):
    encoded = {}
    for language, conversations in corpora.items():
        rows = []
        for messages in conversations:
            inputs, labels = encode_instruction_example(messages, tokenizer, block_size)
            rows.append((inputs, labels))
        if not rows:
            raise ValueError(f"language {language!r} has no usable instruction examples")
        encoded[language] = rows
    return encoded


def collate_examples(examples, indices, block_size, device):
    batch = [examples[index] for index in indices]
    width = min(block_size, max(len(inputs) for inputs, _ in batch))
    tokens = torch.zeros((len(batch), width), dtype=torch.long)
    labels = torch.full((len(batch), width), -100, dtype=torch.long)
    for row, (input_ids, target_ids) in enumerate(batch):
        length = min(width, len(input_ids))
        tokens[row, :length] = torch.tensor(input_ids[:length], dtype=torch.long)
        labels[row, :length] = torch.tensor(target_ids[:length], dtype=torch.long)
    return tokens.to(device), labels.to(device)


def sample_instruction_batch(corpora, batch_size, block_size, device, generator=None):
    languages = sorted(corpora)
    if not languages:
        raise ValueError("at least one language corpus is required")
    selected = []
    for _ in range(batch_size):
        lang_index = int(torch.randint(len(languages), (1,), generator=generator).item())
        examples = corpora[languages[lang_index]]
        example_index = int(torch.randint(len(examples), (1,), generator=generator).item())
        selected.append((examples, example_index))
    rows = [selected[i][0][selected[i][1]] for i in range(batch_size)]
    return collate_examples(rows, list(range(batch_size)), block_size, device)


def save_training_state(path, model, optimizer, generator, step, seed,
                       data_fingerprint, training_config):
    """Atomically persist all state needed to resume an SFT run."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    torch.save({
        "format": "new-star-sft-resume-v1",
        "step": step,
        "model_state": model.state_dict(),
        "optimizer_state": optimizer.state_dict(),
        "batch_generator_state": generator.get_state(),
        "torch_rng_state": torch.get_rng_state(),
        "cuda_rng_states": torch.cuda.get_rng_state_all() if torch.cuda.is_available() else None,
        "seed": seed,
        "data_fingerprint": data_fingerprint,
        "training_config": training_config,
    }, temporary)
    temporary.replace(path)


def evaluate_sft(model, corpora, batch_size, block_size, device):
    """Return a macro-average of per-language token-weighted validation losses."""
    if batch_size < 1 or block_size < 1:
        raise ValueError("batch_size and block_size must be positive")
    if not corpora or any(not examples for examples in corpora.values()):
        raise ValueError("each validation language must contain at least one example")
    model.eval()
    language_losses = []
    with torch.inference_mode():
        for language in sorted(corpora):
            examples = corpora[language]
            weighted_loss = 0.0
            token_count = 0
            for start in range(0, len(examples), batch_size):
                indices = list(range(start, min(start + batch_size, len(examples))))
                x, y = collate_examples(examples, indices, block_size, device)
                supervised_tokens = int((y != -100).sum().item())
                if supervised_tokens == 0:
                    raise ValueError(f"validation batch has no assistant targets for {language}")
                _, loss = model(x, y)
                if loss is None or not torch.isfinite(loss):
                    raise RuntimeError(f"non-finite validation loss for {language}")
                weighted_loss += float(loss.item()) * supervised_tokens
                token_count += supervised_tokens
            language_losses.append(weighted_loss / token_count)
    return sum(language_losses) / len(language_losses)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True, help="JSONL with language, split, and messages")
    parser.add_argument("--manifest", required=True, help="dataset provenance JSON; required before SFT")
    parser.add_argument("--output", default="artifacts/newstar-sft.pt")
    parser.add_argument("--steps", type=int, default=1000)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--block-size", type=int, default=256)
    parser.add_argument("--width", type=int, default=128)
    parser.add_argument("--layers", type=int, default=4)
    parser.add_argument("--heads", type=int, default=4)
    parser.add_argument("--learning-rate", type=float, default=1e-4)
    parser.add_argument("--max-vocab-size", type=int, default=8192)
    parser.add_argument("--tokenizer", choices=("char", "bpe"), default="char", help="Tokenizer for a new SFT model when --base-checkpoint is omitted")
    parser.add_argument("--base-checkpoint", help="Optional pretrained New Star checkpoint to fine-tune; preserves its exact model config and tokenizer")
    parser.add_argument("--eval-interval", type=int, default=100)
    parser.add_argument("--resume-from", help="Resume optimizer/model/RNG state from a .resume.pt checkpoint")
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    if min(args.steps, args.batch_size, args.block_size, args.width,
           args.layers, args.heads, args.eval_interval) < 1:
        parser.error("steps, batch-size, block-size, width, layers, heads, and eval-interval must be positive")
    if args.max_vocab_size < 256:
        parser.error("--max-vocab-size must be at least 256")
    if args.width % args.heads:
        parser.error("--width must be divisible by --heads")
    if args.device.startswith("cuda") and not torch.cuda.is_available():
        parser.error("CUDA was requested but is not available in this PyTorch environment")

    torch.manual_seed(args.seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)
    try:
        audit_report = audit_corpus(args.data, args.manifest)
        if not audit_report["ok"]:
            parser.error("instruction corpus audit failed: " + "; ".join(audit_report["errors"]))
        train_messages, validation_messages = load_instruction_corpora(args.data)
        for language, conversations in train_messages.items():
            if not conversations:
                raise ValueError(f"language {language!r} has no training conversations")
            if not validation_messages.get(language):
                raise ValueError(f"language {language!r} has no validation conversations")
        tokenizer_text = "\n".join(
            message["content"]
            for conversations in train_messages.values()
            for messages in conversations
            for message in messages
        )
        base_checkpoint = None
        if args.base_checkpoint:
            base_path = Path(args.base_checkpoint)
            if not base_path.is_file():
                raise ValueError(f"base checkpoint does not exist: {base_path}")
            try:
                base_checkpoint = torch.load(base_path, map_location="cpu", weights_only=True)
            except (OSError, RuntimeError, ValueError) as exc:
                raise ValueError(f"could not load base checkpoint: {exc}") from exc
            if base_checkpoint.get("format") not in {
                "new-star-byte-bpe-gpt-v1", "new-star-unicode-char-gpt-v1"
            }:
                raise ValueError("unsupported base checkpoint format")
            tokenizer = tokenizer_from_dict(base_checkpoint.get("tokenizer"))
            base_config = base_checkpoint.get("config", {})
            if int(base_config.get("block_size", 0)) != args.block_size:
                raise ValueError("--block-size must match the base checkpoint")
            config = NewStarConfig(**base_config)
            if config.vocab_size != len(tokenizer):
                raise ValueError("base checkpoint vocabulary size does not match its tokenizer")
        else:
            tokenizer = (
                ByteBPETokenizer.train(tokenizer_text, max_merges=args.max_vocab_size - 256)
                if args.tokenizer == "bpe"
                else UnicodeCharTokenizer.train(tokenizer_text, args.max_vocab_size)
            )
            config = NewStarConfig(vocab_size=len(tokenizer), block_size=args.block_size,
                                   width=args.width, layers=args.layers, heads=args.heads)
        train_data = encode_corpora(train_messages, tokenizer, args.block_size)
        validation_data = encode_corpora(validation_messages, tokenizer, args.block_size)
    except ValueError as exc:
        parser.error(str(exc))

    model = NewStarGPT(config).to(args.device)
    if args.base_checkpoint:
        try:
            model.load_state_dict(base_checkpoint["state_dict"], strict=True)
        except (KeyError, RuntimeError) as exc:
            parser.error(f"base checkpoint weights do not match its model config: {exc}")
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate)
    generator = torch.Generator(device="cpu").manual_seed(args.seed + 1)
    data_fingerprint = hashlib.sha256(Path(args.data).read_bytes()).hexdigest()
    tokenizer_fingerprint = hashlib.sha256(json.dumps(
        tokenizer.to_dict(), ensure_ascii=False, sort_keys=True
    ).encode("utf-8")).hexdigest()
    training_config = {
        "seed": args.seed,
        "data_fingerprint": data_fingerprint,
        "tokenizer_fingerprint": tokenizer_fingerprint,
        "learning_rate": args.learning_rate,
        "batch_size": args.batch_size,
        "block_size": args.block_size,
        "width": args.width,
        "layers": args.layers,
        "heads": args.heads,
        "max_vocab_size": args.max_vocab_size,
        "tokenizer_type": args.tokenizer,
        "data_format": "instruction-jsonl",
    }
    start_step = 0
    if args.resume_from:
        resume_path = Path(args.resume_from)
        if not resume_path.is_file():
            parser.error(f"resume checkpoint does not exist: {resume_path}")
        try:
            resume = torch.load(resume_path, map_location=args.device, weights_only=True)
        except (OSError, RuntimeError, ValueError) as exc:
            parser.error(f"could not load resume checkpoint: {exc}")
        if resume.get("format") != "new-star-sft-resume-v1":
            parser.error("unsupported SFT resume checkpoint format")
        if resume.get("data_fingerprint") != data_fingerprint:
            parser.error("resume data does not match the original training dataset")
        if resume.get("training_config") != training_config:
            parser.error("resume training configuration must match the original run")
        if resume.get("seed") != args.seed:
            parser.error("resume seed does not match the original run")
        start_step = int(resume.get("step", 0))
        if start_step < 0 or start_step >= args.steps:
            parser.error("--steps must be greater than the saved resume step")
        model.load_state_dict(resume["model_state"])
        optimizer.load_state_dict(resume["optimizer_state"])
        generator.set_state(resume["batch_generator_state"])
        torch.set_rng_state(resume["torch_rng_state"].cpu())
        if torch.cuda.is_available() and resume.get("cuda_rng_states") is not None:
            torch.cuda.set_rng_state_all(
                [state.cpu() for state in resume["cuda_rng_states"]]
            )
    final_validation_loss = None
    for step in range(start_step + 1, args.steps + 1):
        model.train()
        x, y = sample_instruction_batch(
            train_data, args.batch_size, args.block_size, args.device, generator
        )
        _, loss = model(x, y)
        if loss is None or not torch.isfinite(loss):
            raise RuntimeError("training produced a non-finite loss")
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        optimizer.step()
        if step == 1 or step % args.eval_interval == 0 or step == args.steps:
            validation_loss = evaluate_sft(
                model, validation_data, args.batch_size, args.block_size, args.device
            )
            final_validation_loss = validation_loss
            resume_path = Path(args.output).with_suffix(Path(args.output).suffix + ".resume.pt")
            save_training_state(
                resume_path, model, optimizer, generator, step, args.seed,
                data_fingerprint, training_config,
            )
            print(
                f"step={step} train_loss={loss.item():.4f} "
                f"macro_language_val_loss={validation_loss:.4f}",
                flush=True,
            )

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    torch.save({
        "format": ("new-star-byte-bpe-gpt-v1" if args.tokenizer == "bpe" else "new-star-unicode-char-gpt-v1"),
        "config": vars(config),
        "tokenizer": tokenizer.to_dict(),
        "state_dict": model.cpu().state_dict(),
    }, output)
    metadata = {
        "training_method": "supervised-fine-tuning-response-only",
        "training_steps": args.steps,
        "seed": args.seed,
        "learning_rate": args.learning_rate,
        "batch_size": args.batch_size,
        "block_size": args.block_size,
        "model_config": vars(config),
        "final_validation_loss": final_validation_loss,
        "resumed_from_step": start_step,
        "languages": sorted(train_data),
        "train_examples_by_language": {lang: len(rows) for lang, rows in train_data.items()},
        "validation_examples_by_language": {lang: len(rows) for lang, rows in validation_data.items()},
        "validation_metric": "unweighted macro-average of per-language token-weighted assistant-response cross-entropy",
        "source_data": str(Path(args.data).name),
        "data_fingerprint": data_fingerprint,
        "tokenizer_fingerprint": tokenizer_fingerprint,
        "evaluation_protocol": "sft-response-only-macro-language-token-ce-v1",
    }
    output.with_suffix(output.suffix + ".corpus-audit.json").write_text(
        json.dumps(audit_report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    output.with_suffix(output.suffix + ".json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"Saved New Star SFT checkpoint: {output}")


if __name__ == "__main__":
    main()
