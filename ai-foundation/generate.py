"""Generate text from a locally trained New Star checkpoint."""
from __future__ import annotations

import argparse
import torch

from model import NewStarConfig, NewStarGPT


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
    if checkpoint.get("format") != "new-star-byte-gpt-v1":
        parser.error("Unsupported checkpoint format")
    config = NewStarConfig(**checkpoint["config"])
    model = NewStarGPT(config).to(args.device)
    model.load_state_dict(checkpoint["state_dict"])
    prompt = torch.tensor(list(args.prompt.encode("utf-8")), dtype=torch.long,
                          device=args.device).unsqueeze(0)
    if prompt.shape[1] == 0:
        prompt = torch.zeros((1, 1), dtype=torch.long, device=args.device)
    output = model.generate(prompt, args.tokens, args.temperature)[0].tolist()
    print(bytes(output).decode("utf-8", errors="replace"))


if __name__ == "__main__":
    main()
