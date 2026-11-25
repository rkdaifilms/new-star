"""Validate instruction/chat JSONL and build response-only causal-LM examples."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

ALLOWED_ROLES = {"system", "user", "assistant"}


def validate_messages(messages: Any, line_number: int = 0) -> list[dict[str, str]]:
    prefix = f"line {line_number}: " if line_number else ""
    if not isinstance(messages, list) or not messages:
        raise ValueError(prefix + "messages must be a non-empty list")
    normalized: list[dict[str, str]] = []
    expected = "user"
    seen_user = False
    for index, message in enumerate(messages):
        if not isinstance(message, dict):
            raise ValueError(prefix + f"message {index} must be an object")
        role, content = message.get("role"), message.get("content")
        if not isinstance(role, str) or role not in ALLOWED_ROLES:
            raise ValueError(prefix + f"message {index} has an unsupported role")
        if not isinstance(content, str) or not content.strip():
            raise ValueError(prefix + f"message {index} requires non-empty content")
        if role == "system":
            if index != 0 or seen_user:
                raise ValueError(prefix + "system message is allowed only at the beginning")
        elif role == "user":
            if expected != "user":
                raise ValueError(prefix + "messages must alternate user and assistant turns")
            seen_user, expected = True, "assistant"
        else:
            if expected != "assistant":
                raise ValueError(prefix + "assistant message must follow a user message")
            expected = "user"
        normalized.append({"role": role, "content": content.strip()})
    if not seen_user or normalized[-1]["role"] != "assistant":
        raise ValueError(prefix + "conversation must end with an assistant response")
    return normalized


def load_instruction_corpora(path: str | Path):
    """Load JSONL records with language, split, and messages fields."""
    train: dict[str, list[list[dict[str, str]]]] = {}
    validation: dict[str, list[list[dict[str, str]]]] = {}
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
            language, split = record.get("language"), record.get("split")
            if not isinstance(language, str) or not language.strip():
                raise ValueError(f"line {line_number} requires a language label")
            if not isinstance(split, str) or split not in {"train", "validation"}:
                raise ValueError(f"line {line_number} split must be train or validation")
            messages = validate_messages(record.get("messages"), line_number)
            target = train if split == "train" else validation
            target.setdefault(language.strip(), []).append(messages)
    if not train:
        raise ValueError("instruction JSONL has no training records")
    if set(train) != set(validation):
        missing, extra = sorted(set(train) - set(validation)), sorted(set(validation) - set(train))
        raise ValueError(
            f"train/validation languages differ; missing validation={missing}, validation-only={extra}"
        )
    return train, validation


def encode_instruction_example(messages, tokenizer, max_length: int):
    """Return shifted IDs and labels; user/system targets are masked with -100."""
    if not isinstance(max_length, int) or isinstance(max_length, bool) or max_length < 2:
        raise ValueError("max_length must be an integer of at least 2")
    if not callable(getattr(tokenizer, "encode", None)):
        raise ValueError("tokenizer must provide an encode(text) method")
    messages = validate_messages(messages)
    token_ids: list[int] = []
    target_mask: list[bool] = []
    for message in messages:
        segment = tokenizer.encode(f"{message['role']}: {message['content']}\n")
        token_ids.extend(segment)
        target_mask.extend([message["role"] == "assistant"] * len(segment))
    start = max(0, len(token_ids) - (max_length + 1))
    token_ids, target_mask = token_ids[start:], target_mask[start:]
    inputs = token_ids[:-1]
    labels = [
        token_ids[index + 1] if target_mask[index + 1] else -100
        for index in range(len(inputs))
    ]
    if not any(label != -100 for label in labels):
        raise ValueError("example has no assistant response tokens inside max_length")
    return inputs, labels
