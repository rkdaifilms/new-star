"""Generate text from a locally trained New Star checkpoint."""
from __future__ import annotations

import argparse
import torch

from model import NewStarConfig, NewStarGPT
from tokenizer import tokenizer_from_dict


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--prompt", default="")
    parser.add_argument("--tokens", type=int, default=100)
    parser.add_argument("--temperature", type=float, default=0.8)
    parser.add_argument("--greedy", action="store_true", help="Use deterministic argmax decoding instead of sampling")
    parser.add_argument("--seed", type=int, help="Isolate sampling randomness with this reproducible seed")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    if args.tokens < 0:
        parser.error("--tokens cannot be negative")
    checkpoint = torch.load(args.checkpoint, map_location=args.device, weights_only=True)
    checkpoint_format = checkpoint.get("format")
    if checkpoint_format not in {"new-star-byte-gpt-v1", "new-star-unicode-char-gpt-v1", "new-star-byte-bpe-gpt-v1"}:
        parser.error("Unsupported checkpoint format")
    config = NewStarConfig(**checkpoint["config"])
    model = NewStarGPT(config).to(args.device)
    model.load_state_dict(checkpoint["state_dict"])
    if checkpoint_format in {"new-star-unicode-char-gpt-v1", "new-star-byte-bpe-gpt-v1"}:
        tokenizer = tokenizer_from_dict(checkpoint["tokenizer"])
        encoded = tokenizer.encode(args.prompt)
        if not encoded:
            encoded = [0]
    else:
        encoded = list(args.prompt.encode("utf-8")) or [0]
    prompt = torch.tensor(encoded, dtype=torch.long, device=args.device).unsqueeze(0)
    if args.seed is not None and not 0 <= args.seed <= 2**63 - 1:
        parser.error("--seed must be between 0 and 2^63-1")
    generator = torch.Generator(device=prompt.device).manual_seed(args.seed) if args.seed is not None else None
    output = model.generate(
        prompt, args.tokens, args.temperature, do_sample=not args.greedy,
        generator=generator,
    )[0].tolist()
    if checkpoint_format in {"new-star-unicode-char-gpt-v1", "new-star-byte-bpe-gpt-v1"}:
        print(tokenizer.decode(output))
    else:
        print(bytes(output).decode("utf-8", errors="replace"))


if __name__ == "__main__":
    main()
