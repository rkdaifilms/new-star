"""Crash-safe local artifact writers used by the New Star training pipeline."""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

import torch


def atomic_torch_save(payload: Any, destination: str | Path) -> Path:
    """Write a PyTorch artifact beside its destination, then atomically replace it."""
    target = Path(destination)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".tmp")
    try:
        with temporary.open("wb") as stream:
            torch.save(payload, stream)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(target)
    finally:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass
    return target


def atomic_write_json(payload: Any, destination: str | Path) -> Path:
    """Write UTF-8 JSON atomically so interrupted runs cannot leave partial metadata."""
    target = Path(destination)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".tmp")
    try:
        encoded = json.dumps(payload, ensure_ascii=False, indent=2, allow_nan=False)
        with temporary.open("w", encoding="utf-8", newline="\n") as stream:
            stream.write(encoded)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(target)
    finally:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass
    return target
