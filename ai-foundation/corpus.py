"""Load explicitly language-tagged JSONL corpora with train/validation splits."""
from __future__ import annotations

import json
from collections import defaultdict
from pathlib import Path


def load_language_corpora(path: str | Path) -> tuple[dict[str, str], dict[str, str]]:
    """Return train and validation text grouped by language.

    Each non-empty JSONL line must contain language, split (train|validation),
    and text fields. Language labels are metadata, not automatically detected.
    """
    train: dict[str, list[str]] = defaultdict(list)
    validation: dict[str, list[str]] = defaultdict(list)
    with Path(path).open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON on line {line_number}") from exc
            if not isinstance(record, dict):
                raise ValueError(f"line {line_number} must be a JSON object")
            language = record.get("language")
            split = record.get("split")
            text = record.get("text")
            if not isinstance(language, str) or not language.strip():
                raise ValueError(f"line {line_number} requires a language label")
            if split not in {"train", "validation"}:
                raise ValueError(f"line {line_number} split must be train or validation")
            if not isinstance(text, str) or not text.strip():
                raise ValueError(f"line {line_number} requires non-empty text")
            target = train if split == "train" else validation
            target[language.strip()].append(text)
    if not train:
        raise ValueError("JSONL corpus has no training records")
    if set(train) != set(validation):
        missing = sorted(set(train) - set(validation))
        extra = sorted(set(validation) - set(train))
        raise ValueError(f"train/validation languages differ; missing validation={missing}, validation-only={extra}")
    train_text = {language: "\n".join(parts) for language, parts in train.items()}
    validation_text = {language: "\n".join(parts) for language, parts in validation.items()}
    for language in train_text:
        if not train_text[language].strip() or not validation_text[language].strip():
            raise ValueError(f"language {language!r} needs non-empty train and validation text")
    return train_text, validation_text


def combine_languages(corpora: dict[str, str]) -> str:
    """Combine corpora in stable language order for tokenizer vocabulary building."""
    if not corpora:
        raise ValueError("at least one language corpus is required")
    return "\n".join(corpora[key] for key in sorted(corpora))


def load_language_validation_corpora(path: str | Path) -> dict[str, str]:
    """Load only held-out validation records; training records are optional."""
    validation: dict[str, list[str]] = defaultdict(list)
    with Path(path).open(encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid JSON on line {line_number}") from exc
            if not isinstance(record, dict):
                raise ValueError(f"line {line_number} must be a JSON object")
            language = record.get("language")
            split = record.get("split")
            text = record.get("text")
            if not isinstance(language, str) or not language.strip():
                raise ValueError(f"line {line_number} requires a language label")
            if split not in {"train", "validation"}:
                raise ValueError(f"line {line_number} split must be train or validation")
            if not isinstance(text, str) or not text.strip():
                raise ValueError(f"line {line_number} requires non-empty text")
            if split == "validation":
                validation[language.strip()].append(text)
    if not validation:
        raise ValueError("JSONL corpus has no validation records")
    return {language: "\n".join(parts) for language, parts in validation.items()}



def supported_language_codes(catalog_path: str | Path | None = None) -> set[str]:
    """Read the canonical gateway language catalog without maintaining a stale copy."""
    import re

    catalog = Path(catalog_path) if catalog_path else (
        Path(__file__).resolve().parents[1] /
        "supabase" / "functions" / "ai-gateway" / "language-catalog.ts"
    )
    if not catalog.is_file():
        raise ValueError(f"canonical language catalog is missing: {catalog}")
    source = catalog.read_text(encoding="utf-8")
    codes = set(re.findall(r'^\\s{2}([a-z]{2,3}): "', source, flags=re.MULTILINE))
    if not codes:
        raise ValueError("canonical language catalog contains no language entries")
    return codes


def require_complete_language_coverage(validation_by_language: dict[str, str],
                                       catalog_path: str | Path | None = None) -> None:
    """Fail closed when a purported all-language evaluation omits catalog languages."""
    supported = supported_language_codes(catalog_path)
    provided = set(validation_by_language)
    missing = sorted(supported - provided)
    unsupported = sorted(provided - supported)
    if missing or unsupported:
        details = []
        if missing:
            details.append("missing=" + ",".join(missing))
        if unsupported:
            details.append("unsupported=" + ",".join(unsupported))
        raise ValueError("language evaluation coverage mismatch: " + "; ".join(details))
