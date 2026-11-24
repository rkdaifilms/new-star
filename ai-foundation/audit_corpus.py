"""Audit multilingual text JSONL splits and dataset provenance before training.

This tool checks structural completeness, minimum character counts, exact
normalized duplicates within/across splits, and a human-supplied provenance
manifest. It does not verify copyright ownership or detect paraphrase leakage.
"""
from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any


REQUIRED_MANIFEST_FIELDS = ("dataset_name", "source", "license", "permission_basis")


def normalize_text(value: str) -> str:
    """Normalize whitespace and case for conservative exact-duplicate checks."""
    return re.sub(r"\\s+", " ", value).strip().casefold()


def audit_corpus(
    data_path: str | Path,
    manifest_path: str | Path,
    min_train_chars: int = 1,
    min_validation_chars: int = 1,
) -> dict[str, Any]:
    if min_train_chars < 1 or min_validation_chars < 1:
        raise ValueError("minimum character thresholds must be positive")

    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("manifest must be a JSON object")
    missing = [
        field for field in REQUIRED_MANIFEST_FIELDS
        if not isinstance(manifest.get(field), str) or not manifest[field].strip()
    ]
    if missing:
        raise ValueError(f"manifest missing required fields: {', '.join(missing)}")

    records: dict[str, dict[str, list[str]]] = defaultdict(
        lambda: {"train": [], "validation": []}
    )
    with Path(data_path).open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON on line {line_number}") from exc
            if not isinstance(row, dict):
                raise ValueError(f"line {line_number} must be a JSON object")
            language, split, text = row.get("language"), row.get("split"), row.get("text")
            if not isinstance(language, str) or not language.strip():
                raise ValueError(f"line {line_number} requires a language label")
            if split not in {"train", "validation"}:
                raise ValueError(f"line {line_number} split must be train or validation")
            if not isinstance(text, str) or not text.strip():
                raise ValueError(f"line {line_number} requires non-empty text")
            records[language.strip()][split].append(text.strip())

    if not records:
        raise ValueError("corpus has no records")
    report: dict[str, Any] = {
        "audit_version": 1,
        "provenance": {field: manifest[field].strip() for field in REQUIRED_MANIFEST_FIELDS},
        "languages": {},
        "errors": [],
        "warnings": [],
    }
    for language in sorted(records):
        splits = records[language]
        train = splits["train"]
        validation = splits["validation"]
        train_chars = sum(len(item) for item in train)
        validation_chars = sum(len(item) for item in validation)
        train_norm = [normalize_text(item) for item in train]
        validation_norm = [normalize_text(item) for item in validation]
        train_set, validation_set = set(train_norm), set(validation_norm)
        train_duplicates = len(train_norm) - len(train_set)
        validation_duplicates = len(validation_norm) - len(validation_set)
        overlap = len(train_set & validation_set)
        language_errors = []
        if not train:
            language_errors.append("missing train records")
        if not validation:
            language_errors.append("missing validation records")
        if train_chars < min_train_chars:
            language_errors.append(
                f"train characters {train_chars} below minimum {min_train_chars}"
            )
        if validation_chars < min_validation_chars:
            language_errors.append(
                f"validation characters {validation_chars} below minimum {min_validation_chars}"
            )
        if overlap:
            language_errors.append(f"{overlap} exact normalized train/validation duplicates")
        report["languages"][language] = {
            "train_records": len(train),
            "validation_records": len(validation),
            "train_characters": train_chars,
            "validation_characters": validation_chars,
            "train_duplicate_records": train_duplicates,
            "validation_duplicate_records": validation_duplicates,
            "cross_split_duplicate_texts": overlap,
            "errors": language_errors,
        }
        report["errors"].extend(f"{language}: {error}" for error in language_errors)
        if train_duplicates or validation_duplicates:
            report["warnings"].append(
                f"{language}: duplicate records within a split "
                f"(train={train_duplicates}, validation={validation_duplicates})"
            )
    report["ok"] = not report["errors"]
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", required=True, help="multilingual text JSONL")
    parser.add_argument("--manifest", required=True, help="dataset provenance JSON")
    parser.add_argument("--min-train-chars", type=int, default=1)
    parser.add_argument("--min-validation-chars", type=int, default=1)
    parser.add_argument("--report-json", help="optional path to write the audit report")
    args = parser.parse_args()
    report = audit_corpus(
        args.data, args.manifest, args.min_train_chars, args.min_validation_chars
    )
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.report_json:
        output = Path(args.report_json)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered + "\\n", encoding="utf-8")
    if not report["ok"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
