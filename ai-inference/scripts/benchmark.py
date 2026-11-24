#!/usr/bin/env python3
"""Measure New Star inference readiness, time-to-first-token, and throughput.

Uses only Python's standard library. Run against a deployed service; never prints
the bearer token or full model output.
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import time
import urllib.error
import urllib.request


def request(url: str, token: str, body: dict | None = None, stream: bool = False):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Authorization": f"Bearer {token}", "Accept": "text/event-stream" if stream else "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method="GET" if body is None else "POST")
    return urllib.request.urlopen(req, timeout=600)


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, round((len(ordered) - 1) * p)))]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="Service URL, e.g. http://127.0.0.1:8000")
    parser.add_argument("--model", default="new-star-local")
    parser.add_argument("--requests", type=int, default=3)
    parser.add_argument("--max-tokens", type=int, default=128)
    args = parser.parse_args()
    token = os.getenv("MODEL_TOKEN", "")
    if not token:
        parser.error("Set MODEL_TOKEN in the environment; do not pass secrets as command-line arguments.")
    if args.requests < 1 or args.requests > 20 or args.max_tokens < 1 or args.max_tokens > 512:
        parser.error("--requests must be 1..20 and --max-tokens must be 1..512")

    base = args.base_url.rstrip("/")
    try:
        with request(base + "/healthz", token) as response:
            health = json.load(response)
        print("health:", json.dumps(health, ensure_ascii=False))
        if not health.get("ok") or not health.get("model_loaded"):
            print("NOT READY: model is not loaded; no inference benchmark was run.")
            return 2
        with request(base + "/v1/models", token) as response:
            models = json.load(response)
        print("models endpoint: OK")

        first_tokens: list[float] = []
        totals: list[float] = []
        rates: list[float] = []
        for index in range(args.requests):
            body = {
                "model": args.model,
                "messages": [{"role": "user", "content": "Reply with a short greeting in Hindi."}],
                "max_tokens": args.max_tokens,
                "stream": True,
            }
            started = time.perf_counter()
            first = None
            chunks = 0
            with request(base + "/v1/chat/completions", token, body, stream=True) as response:
                for raw in response:
                    line = raw.decode("utf-8", errors="replace").strip()
                    if not line.startswith("data: ") or line == "data: [DONE]":
                        continue
                    payload = json.loads(line[6:])
                    delta = payload.get("choices", [{}])[0].get("delta", {})
                    text = delta.get("content", "")
                    if text:
                        chunks += 1
                        if first is None:
                            first = time.perf_counter() - started
            total = time.perf_counter() - started
            if first is None:
                print(f"request {index + 1}: FAILED (no text token received)")
                continue
            first_tokens.append(first)
            totals.append(total)
            rates.append(chunks / total if total else 0.0)
            print(f"request {index + 1}: first-token={first:.3f}s total={total:.3f}s text-chunks={chunks}")

        if not totals:
            print("BENCHMARK FAILED: no successful streamed responses.")
            return 1
        print(f"summary: success={len(totals)}/{args.requests}")
        print(f"TTFT seconds: median={statistics.median(first_tokens):.3f} p95={percentile(first_tokens, 0.95):.3f}")
        print(f"total seconds: median={statistics.median(totals):.3f} p95={percentile(totals, 0.95):.3f}")
        print(f"text-chunks/sec (rough proxy, not tokenizer tokens/sec): median={statistics.median(rates):.2f}")
        print("Note: compare runs on the same hardware, model, quantization, prompt, and token limit.")
        return 0 if len(totals) == args.requests else 1
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        print(f"BENCHMARK ERROR: {type(exc).__name__}: service unreachable or returned invalid data.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
