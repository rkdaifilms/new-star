"""Append-only experiment registry for New Star model checkpoints."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def register_experiment(
    metadata_path: str | Path,
    registry_path: str | Path = "artifacts/experiments.jsonl",
    stage: str | None = None,
) -> dict[str, Any]:
    """Record a completed checkpoint and its sidecar metadata as one JSONL event."""
    metadata_path = Path(metadata_path)
    if not metadata_path.is_file():
        raise FileNotFoundError(f"checkpoint metadata not found: {metadata_path}")
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise ValueError("checkpoint metadata must be valid JSON") from exc
    if not isinstance(metadata, dict):
        raise ValueError("checkpoint metadata must be a JSON object")

    checkpoint_path = Path(str(metadata_path)[:-5]) if metadata_path.name.endswith(".json") else None
    if checkpoint_path is None or not checkpoint_path.is_file():
        raise FileNotFoundError("checkpoint file matching the .json sidecar was not found")

    audit_path = checkpoint_path.with_suffix(checkpoint_path.suffix + ".corpus-audit.json")
    audit = None
    if audit_path.is_file():
        audit = json.loads(audit_path.read_text(encoding="utf-8"))
        if not isinstance(audit, dict):
            raise ValueError("corpus audit sidecar must be a JSON object")
        if audit.get("ok") is not True:
            raise ValueError("refusing to register a checkpoint with a failed corpus audit")

    inferred_stage = stage or (
        "sft" if metadata.get("training_method") == "supervised-fine-tuning-response-only"
        else "pretraining"
    )
    if inferred_stage not in {"pretraining", "sft"}:
        raise ValueError("stage must be pretraining or sft")

    record: dict[str, Any] = {
        "schema_version": 1,
        "run_id": str(uuid.uuid4()),
        "recorded_at": datetime.now(timezone.utc).isoformat(),
        "stage": inferred_stage,
        "checkpoint": str(checkpoint_path),
        "checkpoint_sha256": sha256_file(checkpoint_path),
        "training_steps": metadata.get("training_steps"),
        "final_validation_loss": metadata.get("final_validation_loss"),
        "seed": metadata.get("seed"),
        "languages": metadata.get("languages", []),
        "model_config": metadata.get("model_config", metadata.get("config")),
        "data_fingerprint": metadata.get("data_fingerprint"),
        "corpus_audit": {
            "ok": audit.get("ok"),
            "provenance": audit.get("provenance"),
            "language_count": len(audit.get("languages", {})),
            "audit_path": str(audit_path),
        } if audit is not None else None,
    }
    destination = Path(registry_path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    encoded = (json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    try:
        os.write(descriptor, encoded)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    return record


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", required=True, help="checkpoint .json sidecar")
    parser.add_argument("--registry", default="artifacts/experiments.jsonl")
    parser.add_argument("--stage", choices=("pretraining", "sft"))
    args = parser.parse_args()
    print(json.dumps(
        register_experiment(args.metadata, args.registry, args.stage),
        ensure_ascii=False, indent=2,
    ))


if __name__ == "__main__":
    main()
