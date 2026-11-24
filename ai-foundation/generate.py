"""Generate text from a locally trained New Star checkpoint."""
from __future__ import annotations

import argparse
import torch

from model import NewStarConfig, NewStarGPT
from tokenizer import UnicodeCharTokenizer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--prompt", default="")
    parser.add_argument("--tokens", type=int, default=100)
    parser.add_argument("--temperature", type=float, default=0.8)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    if args.tokens < 0:
        parser.error("--tokens cannot be negative")
    checkpoint = torch.load(args.checkpoint, map_location=args.device, weights_only=True)
    checkpoint_format = checkpoint.get("format")
    if checkpoint_format not in {"new-star-byte-gpt-v1", "new-star-unicode-char-gpt-v1"}:
        parser.error("Unsupported checkpoint format")
    config = NewStarConfig(**checkpoint["config"])
    model = NewStarGPT(config).to(args.device)
    model.load_state_dict(checkpoint["state_dict"])
    if checkpoint_format == "new-star-unicode-char-gpt-v1":
        tokenizer = UnicodeCharTokenizer.from_dict(checkpoint["tokenizer"])
        encoded = tokenizer.encode(args.prompt)
        if not encoded:
            encoded = [0]
    else:
        encoded = list(args.prompt.encode("utf-8")) or [0]
    prompt = torch.tensor(encoded, dtype=torch.long, device=args.device).unsqueeze(0)
    output = model.generate(prompt, args.tokens, args.temperature)[0].tolist()
    if checkpoint_format == "new-star-unicode-char-gpt-v1":
        print(tokenizer.decode(output))
    else:
        print(bytes(output).decode("utf-8", errors="replace"))


if __name__ == "__main__":
    main()
