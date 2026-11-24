"""Measure local New Star generation latency and throughput on CPU or CUDA."""
from __future__ import annotations

import argparse
import json
import platform
import statistics
import time
from pathlib import Path
from typing import Any

import torch

from serve import load_engine


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * fraction)))
    return ordered[index]


def benchmark(
    checkpoint: str,
    prompt: str = "Hello",
    device: str = "cpu",
    warmup: int = 2,
    iterations: int = 5,
    max_new_tokens: int = 32,
    threads: int | None = None,
) -> dict[str, Any]:
    if warmup < 0 or iterations < 1 or max_new_tokens < 1:
        raise ValueError("warmup must be >= 0; iterations and max_new_tokens must be >= 1")
    if threads is not None:
        if threads < 1:
            raise ValueError("threads must be >= 1")
        if device != "cpu":
            raise ValueError("the --threads option is only valid for CPU benchmarks")
        torch.set_num_threads(threads)

    model, tokenizer = load_engine(checkpoint, device)
    encoded = tokenizer.encode(prompt)
    if not encoded:
        raise ValueError("prompt must produce at least one token")
    if len(encoded) > model.config.block_size:
        raise ValueError(f"prompt exceeds model block size ({model.config.block_size})")
    input_ids = torch.tensor(encoded, dtype=torch.long, device=device).unsqueeze(0)

    def run_once() -> tuple[float, int]:
        if device.startswith("cuda"):
            torch.cuda.synchronize(device)
        started = time.perf_counter()
        with torch.inference_mode():
            output = model.generate(
                input_ids, max_new_tokens=max_new_tokens, temperature=1.0,
                top_k=1, top_p=1.0, repetition_penalty=1.0,
            )
        if device.startswith("cuda"):
            torch.cuda.synchronize(device)
        elapsed = time.perf_counter() - started
        return elapsed, int(output.shape[1] - input_ids.shape[1])

    for _ in range(warmup):
        run_once()
    if device.startswith("cuda"):
        torch.cuda.reset_peak_memory_stats(device)

    durations: list[float] = []
    generated_tokens = 0
    for _ in range(iterations):
        elapsed, count = run_once()
        durations.append(elapsed)
        generated_tokens += count

    total_seconds = sum(durations)
    parameters = sum(parameter.numel() for parameter in model.parameters())
    result: dict[str, Any] = {
        "benchmark": "new-star-generation-v1",
        "checkpoint": str(checkpoint),
        "device": device,
        "python": platform.python_version(),
        "pytorch": torch.__version__,
        "platform": platform.platform(),
        "cpu_threads": torch.get_num_threads() if device == "cpu" else None,
        "parameter_count": parameters,
        "prompt_tokens": len(encoded),
        "requested_new_tokens_per_iteration": max_new_tokens,
        "warmup_iterations": warmup,
        "measured_iterations": iterations,
        "generated_tokens": generated_tokens,
        "total_generation_seconds": round(total_seconds, 6),
        "tokens_per_second": round(generated_tokens / total_seconds, 3) if total_seconds else None,
        "latency_seconds": {
            "mean": round(statistics.mean(durations), 6),
            "median": round(statistics.median(durations), 6),
            "p95": round(percentile(durations, 0.95), 6),
            "min": round(min(durations), 6),
            "max": round(max(durations), 6),
        },
    }
    if device.startswith("cuda"):
        result["peak_cuda_memory_bytes"] = torch.cuda.max_memory_allocated(device)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--prompt", default="Hello")
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--warmup", type=int, default=2)
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--max-new-tokens", type=int, default=32)
    parser.add_argument("--threads", type=int)
    parser.add_argument("--output")
    args = parser.parse_args()
    report = benchmark(
        args.checkpoint, args.prompt, args.device, args.warmup,
        args.iterations, args.max_new_tokens, args.threads,
    )
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        destination = Path(args.output)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
