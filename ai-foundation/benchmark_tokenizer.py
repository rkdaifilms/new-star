"""Benchmark New Star byte-BPE token compression and encode/decode throughput."""
from __future__ import annotations

import argparse
import hashlib
import json
import platform
import time
from pathlib import Path
from typing import Any

from bpe_tokenizer import ByteBPETokenizer


def benchmark(
    text: str,
    max_merges: int = 2000,
    min_frequency: int = 2,
    iterations: int = 3,
) -> dict[str, Any]:
    if not text:
        raise ValueError("benchmark text must not be empty")
    if iterations < 1:
        raise ValueError("iterations must be at least one")
    tokenizer = ByteBPETokenizer.train(
        text, max_merges=max_merges, min_frequency=min_frequency
    )
    encoded = tokenizer.encode(text)
    if tokenizer.decode(encoded) != text:
        raise RuntimeError("tokenizer round-trip check failed")

    encode_durations: list[float] = []
    decode_durations: list[float] = []
    for _ in range(iterations):
        started = time.perf_counter()
        tokens = tokenizer.encode(text)
        encode_durations.append(time.perf_counter() - started)
        started = time.perf_counter()
        decoded = tokenizer.decode(tokens)
        decode_durations.append(time.perf_counter() - started)
        if decoded != text:
            raise RuntimeError("tokenizer output changed between benchmark iterations")

    byte_count = len(text.encode("utf-8"))
    char_count = len(text)
    token_count = len(encoded)
    return {
        "benchmark": "new-star-byte-bpe-v1",
        "python": platform.python_version(),
        "text_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "characters": char_count,
        "utf8_bytes": byte_count,
        "vocabulary_size": len(tokenizer),
        "learned_merges": len(tokenizer.merges),
        "tokens": token_count,
        "tokens_per_character": round(token_count / char_count, 6),
        "tokens_per_utf8_byte": round(token_count / byte_count, 6),
        "compression_ratio_bytes_per_token": round(byte_count / token_count, 6),
        "round_trip_verified": True,
        "iterations": iterations,
        "encode_seconds_mean": round(sum(encode_durations) / iterations, 6),
        "decode_seconds_mean": round(sum(decode_durations) / iterations, 6),
        "encode_characters_per_second": round(
            char_count / (sum(encode_durations) / iterations), 3
        ),
        "decode_characters_per_second": round(
            char_count / (sum(decode_durations) / iterations), 3
        ),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True, help="UTF-8 text file with permitted benchmark material")
    parser.add_argument("--max-merges", type=int, default=2000)
    parser.add_argument("--min-frequency", type=int, default=2)
    parser.add_argument("--iterations", type=int, default=3)
    parser.add_argument("--output", help="optional JSON report path")
    args = parser.parse_args()
    text = Path(args.data).read_text(encoding="utf-8")
    report = benchmark(text, args.max_merges, args.min_frequency, args.iterations)
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        destination = Path(args.output)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
