"""Run a real CUDA smoke test and report throughput/memory before training.

This script deliberately fails when CUDA is unavailable; CPU results must not be
reported as GPU validation. It uses synthetic tensors only and writes no model.
"""
from __future__ import annotations

import argparse
import json
import platform
import time

import torch

from hardware import hardware_report, resolve_device, resolve_precision
from model import NewStarConfig, NewStarGPT


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--steps", type=int, default=20)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--block-size", type=int, default=128)
    parser.add_argument("--width", type=int, default=128)
    parser.add_argument("--layers", type=int, default=2)
    parser.add_argument("--heads", type=int, default=4)
    parser.add_argument("--vocab-size", type=int, default=2048)
    parser.add_argument("--precision", choices=("auto", "fp32", "fp16", "bf16"), default="auto")
    parser.add_argument("--report-json", default="artifacts/gpu-validation.json")
    args = parser.parse_args()
    if min(args.steps, args.batch_size, args.block_size, args.width, args.layers, args.heads) < 1:
        parser.error("steps, batch-size, block-size, width, layers and heads must be positive")
    try:
        device = resolve_device(args.device)
        if device.type != "cuda":
            raise ValueError("GPU validation requires a CUDA device; use training CPU tests separately")
        precision = resolve_precision(device, args.precision)
        config = NewStarConfig(vocab_size=args.vocab_size, block_size=args.block_size,
                               width=args.width, layers=args.layers, heads=args.heads)
        model = NewStarGPT(config).to(device)
        optimizer = torch.optim.AdamW(model.parameters(), lr=1e-4)
        x = torch.randint(0, args.vocab_size, (args.batch_size, args.block_size), device=device)
        y = torch.randint(0, args.vocab_size, (args.batch_size, args.block_size), device=device)
        if device.type == "cuda":
            torch.cuda.reset_peak_memory_stats(device)
            torch.cuda.synchronize(device)
        started = time.perf_counter()
        for _ in range(args.steps):
            optimizer.zero_grad(set_to_none=True)
            if precision == "fp32":
                _, loss = model(x, y)
            else:
                dtype = torch.float16 if precision == "fp16" else torch.bfloat16
                with torch.autocast(device_type="cuda", dtype=dtype):
                    _, loss = model(x, y)
            loss.backward()
            optimizer.step()
        torch.cuda.synchronize(device)
        elapsed = time.perf_counter() - started
        report = {
            "ok": True,
            "validation": "real-cuda-forward-backward-optimizer-smoke-test",
            "platform": platform.platform(),
            "hardware": hardware_report(device, precision),
            "steps": args.steps,
            "batch_size": args.batch_size,
            "block_size": args.block_size,
            "tokens_per_second": (args.steps * args.batch_size * args.block_size) / max(elapsed, 1e-9),
            "elapsed_seconds": elapsed,
            "peak_allocated_bytes": torch.cuda.max_memory_allocated(device),
            "peak_reserved_bytes": torch.cuda.max_memory_reserved(device),
            "final_loss": float(loss.detach().float().item()),
        }
        print(json.dumps(report, ensure_ascii=False, indent=2))
        from pathlib import Path
        output = Path(args.report_json)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        return 0
    except Exception as exc:
        print(json.dumps({"ok": False, "error": str(exc), "cuda_available": torch.cuda.is_available()}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
