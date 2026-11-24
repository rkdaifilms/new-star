"""Validate instruction/chat JSONL and build response-only causal-LM examples.

This module prepares supervised fine-tuning (SFT) records. It does not train a
model by itself; callers must train on the returned input/label pairs.
"""
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
        role = message.get("role")
        content = message.get("content")
        if role not in ALLOWED_ROLES:
            raise ValueError(prefix + f"message {index} has an unsupported role")
        if not isinstance(content, str) or not content.strip():
            raise ValueError(prefix + f"message {index} requires non-empty content")
        if role == "system":
            if index != 0 or seen_user:
                raise ValueError(prefix + "system message is allowed only at the beginning")
        elif role == "user":
            if expected != "user":
                raise ValueError(prefix + "messages must alternate user and assistant turns")
            seen_user = True
            expected = "assistant"
        else:
            if expected != "assistant":
                raise ValueError(prefix + "assistant message must follow a user message")
            expected = "user"
        normalized.append({"role": role, "content": content.strip()})
    if not seen_user or normalized[-1]["role"] != "assistant":
        raise ValueError(prefix + "conversation must end with an assistant response")
    return normalized


def load_instruction_corpora(path: str | Path) -> tuple[dict[str, list[list[dict[str, str]]]], dict[str, list[list[dict[str, str]]]]]:
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
            language = record.get("language")
            split = record.get("split")
            if not isinstance(language, str) or not language.strip():
                raise ValueError(f"line {line_number} requires a language label")
            if split not in {"train", "validation"}:
                raise ValueError(f"line {line_number} split must be train or validation")
            messages = validate_messages(record.get("messages"), line_number)
            target = train if split == "train" else validation
            target.setdefault(language.strip(), []).append(messages)
    if not train:
        raise ValueError("instruction JSONL has no training records")
    if set(train) != set(validation):
        missing = sorted(set(train) - set(validation))
        extra = sorted(set(validation) - set(train))
        raise ValueError(
            f"train/validation languages differ; missing validation={missing}, validation-only={extra}"
        )
    return train, validation


def encode_instruction_example(
    messages: list[dict[str, str]], tokenizer, max_length: int
) -> tuple[list[int], list[int]]:
    """Return shifted input IDs and labels; non-assistant targets are -100."""
    if max_length < 2:
        raise ValueError("max_length must be at least 2")
    messages = validate_messages(messages)
    token_ids: list[int] = []
    assistant_target_positions: set[int] = set()
    for message in messages:
        prefix = f"{message['role']}: "
        segment = tokenizer.encode(prefix + message["content"] + "\n")
        start = len(token_ids)
        token_ids.extend(segment)
        if message["role"] == "assistant":
            assistant_target_positions.update(range(start, len(token_ids)))
    token_ids = token_ids[-(max_length + 1):]
    offset = max(0, sum(len(tokenizer.encode(f"{m['role']}: {m['content']}\n")) for m in messages) - len(token_ids))
    # The prefix above includes a space after the role; account for it exactly.
    if offset:
        assistant_target_positions = {p - offset for p in assistant_target_positions if p >= offset}
    inputs = token_ids[:-1]
    labels = [
        token_ids[index + 1] if index + 1 in assistant_target_positions else -100
        for index in range(len(inputs))
    ]
    if not any(label != -100 for label in labels):
        raise ValueError("example has no assistant response tokens inside max_length")
    return inputs, labels
