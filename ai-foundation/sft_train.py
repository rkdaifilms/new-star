"""Supervised fine-tuning for New Star using response-only masked chat targets."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch

from instruction_data import encode_instruction_example, load_instruction_corpora
from model import NewStarConfig, NewStarGPT
from tokenizer import UnicodeCharTokenizer


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
    parser.add_argument("--output", default="artifacts/newstar-sft.pt")
    parser.add_argument("--steps", type=int, default=1000)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--block-size", type=int, default=256)
    parser.add_argument("--width", type=int, default=128)
    parser.add_argument("--layers", type=int, default=4)
    parser.add_argument("--heads", type=int, default=4)
    parser.add_argument("--learning-rate", type=float, default=1e-4)
    parser.add_argument("--max-vocab-size", type=int, default=8192)
    parser.add_argument("--eval-interval", type=int, default=100)
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
        tokenizer = UnicodeCharTokenizer.train(tokenizer_text, args.max_vocab_size)
        train_data = encode_corpora(train_messages, tokenizer, args.block_size)
        validation_data = encode_corpora(validation_messages, tokenizer, args.block_size)
    except ValueError as exc:
        parser.error(str(exc))

    config = NewStarConfig(vocab_size=len(tokenizer), block_size=args.block_size,
                           width=args.width, layers=args.layers, heads=args.heads)
    model = NewStarGPT(config).to(args.device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.learning_rate)
    generator = torch.Generator(device="cpu").manual_seed(args.seed + 1)
    for step in range(1, args.steps + 1):
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
            print(
                f"step={step} train_loss={loss.item():.4f} "
                f"macro_language_val_loss={validation_loss:.4f}",
                flush=True,
            )

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    torch.save({
        "format": "new-star-unicode-char-gpt-v1",
        "config": vars(config),
        "tokenizer": tokenizer.to_dict(),
        "state_dict": model.cpu().state_dict(),
    }, output)
    metadata = {
        "training_method": "supervised-fine-tuning-response-only",
        "training_steps": args.steps,
        "seed": args.seed,
        "languages": sorted(train_data),
        "train_examples_by_language": {lang: len(rows) for lang, rows in train_data.items()},
        "validation_examples_by_language": {lang: len(rows) for lang, rows in validation_data.items()},
        "validation_metric": "unweighted macro-average of per-language batch losses",
        "source_data": str(Path(args.data).name),
    }
    output.with_suffix(output.suffix + ".json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"Saved New Star SFT checkpoint: {output}")


if __name__ == "__main__":
    main()
