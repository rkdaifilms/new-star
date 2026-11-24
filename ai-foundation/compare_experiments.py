"""Compare registered New Star runs without mixing incompatible evaluations."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from typing import Any


def load_runs(path: str | Path) -> list[dict[str, Any]]:
    source = Path(path)
    if not source.is_file():
        raise FileNotFoundError(f"experiment registry not found: {source}")
    runs: list[dict[str, Any]] = []
    for line_number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid registry JSON on line {line_number}") from exc
        if not isinstance(item, dict) or not item.get("run_id") or item.get("stage") not in {"pretraining", "sft"}:
            raise ValueError(f"invalid experiment record on line {line_number}")
        runs.append(item)
    return runs


def _compatibility_reasons(left: dict[str, Any], right: dict[str, Any]) -> list[str]:
    """Explain why two validation losses must not be compared."""
    checks = (
        ("data_fingerprint", "dataset fingerprint"),
        ("tokenizer_fingerprint", "tokenizer fingerprint"),
        ("languages", "language set"),
        ("model_config", "model configuration"),
        ("evaluation_protocol", "evaluation protocol"),
        ("validation_metric", "validation metric"),
    )
    reasons = []
    for key, label in checks:
        left_value, right_value = left.get(key), right.get(key)
        if left_value is None or right_value is None:
            reasons.append(f"missing {label}")
        elif key == "languages":
            if sorted(left_value or []) != sorted(right_value or []):
                reasons.append(f"different {label}")
        elif left_value != right_value:
            reasons.append(f"different {label}")
    for run, label in ((left, "previous"), (right, "latest")):
        loss = run.get("final_validation_loss")
        if not isinstance(loss, (int, float)) or not math.isfinite(loss):
            reasons.append(f"{label} validation loss is missing or non-finite")
    return reasons


def compare_runs(
    registry_path: str | Path,
    stage: str,
    verify_checkpoints: bool = False,
) -> dict[str, Any]:
    if stage not in {"pretraining", "sft"}:
        raise ValueError("stage must be pretraining or sft")
    runs = [run for run in load_runs(registry_path) if run["stage"] == stage]
    if not runs:
        raise ValueError(f"no {stage} runs found in registry")

    latest = runs[-1]
    previous = runs[-2] if len(runs) > 1 else None
    reasons = _compatibility_reasons(previous, latest) if previous is not None else [
        "only one run is registered"
    ]
    comparable = previous is not None and not reasons
    delta = (
        latest["final_validation_loss"] - previous["final_validation_loss"]
        if comparable else None
    )

    # A "best" result is meaningful only inside the latest run's exact
    # evaluation-compatibility group. Never rank unrelated datasets/configs.
    comparable_runs = [
        run for run in runs
        if not _compatibility_reasons(run, latest)
    ]
    best = min(
        comparable_runs,
        key=lambda run: run["final_validation_loss"],
    ) if comparable_runs else None

    checkpoint_checks = []
    if verify_checkpoints:
        for run in runs:
            checkpoint = Path(str(run.get("checkpoint", "")))
            exists = checkpoint.is_file()
            actual = hashlib.sha256(checkpoint.read_bytes()).hexdigest() if exists else None
            checkpoint_checks.append({
                "run_id": run["run_id"],
                "exists": exists,
                "hash_matches": actual == run.get("checkpoint_sha256") if exists else False,
            })

    return {
        "stage": stage,
        "run_count": len(runs),
        "latest_run_id": latest.get("run_id"),
        "latest_validation_loss": latest.get("final_validation_loss"),
        "best_run_id": best.get("run_id") if best else None,
        "best_validation_loss": best.get("final_validation_loss") if best else None,
        "best_scope": "runs matching latest dataset, tokenizer, languages, model config, and evaluation protocol",
        "previous_run_id": previous.get("run_id") if previous else None,
        "comparable_to_previous": comparable,
        "incompatibility_reasons": reasons,
        "validation_loss_delta_latest_minus_previous": delta,
        "interpretation": (
            "lower validation loss is better; negative delta indicates improvement"
            if comparable else
            "validation losses are not directly comparable; see incompatibility_reasons"
        ),
        "checkpoint_checks": checkpoint_checks if verify_checkpoints else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", default="artifacts/experiments.jsonl")
    parser.add_argument("--stage", choices=("pretraining", "sft"), required=True)
    parser.add_argument("--verify-checkpoints", action="store_true")
    parser.add_argument("--output", help="optional JSON report path")
    args = parser.parse_args()
    report = compare_runs(args.registry, args.stage, args.verify_checkpoints)
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        destination = Path(args.output)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
