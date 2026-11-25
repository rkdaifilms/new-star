"""Evaluate generated responses against reviewed multilingual references.

Input JSONL records: {"language":"ne","prompt":"...","reference":"..."}.
This reports exact match and character n-gram F1 (chrF-style), not human-rated meaning.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import random

import torch

from corpus import require_complete_language_coverage
from model import NewStarConfig, NewStarGPT
from tokenizer import tokenizer_from_dict
from artifact_io import atomic_write_json


def load_cases(path: str | Path) -> list[dict[str, str]]:
    cases = []
    with Path(path).open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, 1):
            if not line.strip():
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON on line {line_number}") from exc
            if not isinstance(item, dict):
                raise ValueError(f"line {line_number} must be a JSON object")
            language, prompt, reference = item.get("language"), item.get("prompt"), item.get("reference")
            if not isinstance(language, str) or not language.strip():
                raise ValueError(f"line {line_number} requires language")
            if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 8000:
                raise ValueError(f"line {line_number} requires a prompt of 1..8000 characters")
            if not isinstance(reference, str) or not reference.strip() or len(reference) > 8000:
                raise ValueError(f"line {line_number} requires a reference of 1..8000 characters")
            cases.append({"language": language.strip(), "prompt": prompt, "reference": reference})
    if not cases:
        raise ValueError("generation evaluation dataset is empty")
    return cases


def char_ngram_f1(reference: str, hypothesis: str, max_order: int = 6) -> float:
    """Compute macro character n-gram F1 across orders 1..max_order."""
    ref = " ".join(reference.casefold().split())
    hyp = " ".join(hypothesis.casefold().split())
    if not ref or not hyp:
        return 1.0 if ref == hyp else 0.0
    scores = []
    for order in range(1, max_order + 1):
        ref_counts = Counter(ref[index:index + order] for index in range(len(ref) - order + 1))
        hyp_counts = Counter(hyp[index:index + order] for index in range(len(hyp) - order + 1))
        overlap = sum((ref_counts & hyp_counts).values())
        precision = overlap / max(1, sum(hyp_counts.values()))
        recall = overlap / max(1, sum(ref_counts.values()))
        scores.append(2 * precision * recall / max(1e-12, precision + recall))
    return sum(scores) / len(scores)


def evaluate_cases(model, tokenizer, cases: list[dict[str, str]], device: str,
                   max_new_tokens: int, seed: int) -> dict:
    random.seed(seed)
    torch.manual_seed(seed)
    by_language: dict[str, list[dict]] = defaultdict(list)
    for case in cases:
        prompt_ids = tokenizer.encode(case["prompt"]) if tokenizer else list(case["prompt"].encode("utf-8"))
        if not prompt_ids:
            prompt_ids = [0]
        prompt = torch.tensor(prompt_ids, dtype=torch.long, device=device).unsqueeze(0)
        with torch.no_grad():
            output_ids = model.generate(prompt, max_new_tokens=max_new_tokens, temperature=0.8,
                                        top_k=50, top_p=0.95)[0].tolist()
        generated_ids = output_ids[len(prompt_ids):]
        if tokenizer:
            generated = tokenizer.decode(generated_ids)
        else:
            generated = bytes(generated_ids).decode("utf-8", errors="replace")
        reference = case["reference"]
        by_language[case["language"]].append({
            "exact_match": generated.strip() == reference.strip(),
            "char_ngram_f1": char_ngram_f1(reference, generated),
            "nonempty": bool(generated.strip()),
            "output_chars": len(generated),
        })
    language_metrics = {}
    for language, rows in sorted(by_language.items()):
        count = len(rows)
        language_metrics[language] = {
            "cases": count,
            "exact_match_rate": sum(row["exact_match"] for row in rows) / count,
            "char_ngram_f1": sum(row["char_ngram_f1"] for row in rows) / count,
            "nonempty_output_rate": sum(row["nonempty"] for row in rows) / count,
            "mean_output_characters": sum(row["output_chars"] for row in rows) / count,
        }
    macro = {
        metric: sum(item[metric] for item in language_metrics.values()) / len(language_metrics)
        for metric in ("exact_match_rate", "char_ngram_f1", "nonempty_output_rate")
    }
    return {"languages": language_metrics, "macro_by_language": macro,
            "language_count": len(language_metrics), "case_count": len(cases)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--cases", required=True, help="Reviewed JSONL with language, prompt and reference")
    parser.add_argument("--max-new-tokens", type=int, default=128)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    parser.add_argument("--seed", type=int, default=1234)
    parser.add_argument("--require-all-supported-languages", action="store_true")
    parser.add_argument("--report-json", required=True)
    args = parser.parse_args()
    if not 1 <= args.max_new_tokens <= 1024:
        parser.error("--max-new-tokens must be 1..1024")
    checkpoint = torch.load(args.checkpoint, map_location=args.device, weights_only=True)
    checkpoint_format = checkpoint.get("format")
    if checkpoint_format not in {"new-star-byte-gpt-v1", "new-star-unicode-char-gpt-v1", "new-star-byte-bpe-gpt-v1"}:
        parser.error("unsupported New Star checkpoint format")
    config = NewStarConfig(**checkpoint["config"])
    model = NewStarGPT(config).to(args.device)
    model.load_state_dict(checkpoint["state_dict"])
    model.eval()
    tokenizer = tokenizer_from_dict(checkpoint["tokenizer"]) if checkpoint_format in {
        "new-star-unicode-char-gpt-v1", "new-star-byte-bpe-gpt-v1"
    } else None
    try:
        cases = load_cases(args.cases)
        if args.require_all_supported_languages:
            require_complete_language_coverage({code: "present" for code in {case["language"] for case in cases}})
        report = evaluate_cases(model, tokenizer, cases, args.device, args.max_new_tokens, args.seed)
    except ValueError as exc:
        parser.error(str(exc))
    report.update({
        "report_format": "new-star-generation-evaluation-v1",
        "checkpoint_sha256": hashlib.sha256(Path(args.checkpoint).read_bytes()).hexdigest(),
        "cases_sha256": hashlib.sha256(Path(args.cases).read_bytes()).hexdigest(),
        "seed": args.seed,
        "max_new_tokens": args.max_new_tokens,
        "metric_note": "Character n-gram F1 is a chrF-style surface metric; it does not measure semantic correctness or human preference.",
    })
    output = Path(args.report_json)
    atomic_write_json(report, output)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
