"""Hardware-aware runtime selection for New Star training jobs."""
from __future__ import annotations

from contextlib import nullcontext
from typing import Any

import torch


def resolve_device(requested: str = "auto") -> torch.device:
    """Resolve auto/cpu/cuda/cuda:N and reject unavailable accelerators."""
    value = requested.strip().lower()
    if value == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "cpu")
    device = torch.device(value)
    if device.type not in {"cpu", "cuda"}:
        raise ValueError("device must be auto, cpu, cuda, or cuda:N")
    if device.type == "cuda":
        if not torch.cuda.is_available():
            raise ValueError("CUDA was requested but this PyTorch runtime has no available CUDA device")
        index = torch.cuda.current_device() if device.index is None else device.index
        if index < 0 or index >= torch.cuda.device_count():
            raise ValueError(f"CUDA device index {index} is not available")
        return torch.device("cuda", index)
    return device


def resolve_precision(device: torch.device, requested: str = "auto") -> str:
    """Choose a safe training precision; CPU remains FP32 in this baseline."""
    value = requested.strip().lower()
    if value not in {"auto", "fp32", "fp16", "bf16"}:
        raise ValueError("precision must be auto, fp32, fp16, or bf16")
    if device.type == "cpu":
        if value not in {"auto", "fp32"}:
            raise ValueError("CPU training currently supports fp32 only")
        return "fp32"
    if value == "auto":
        return "bf16" if torch.cuda.is_bf16_supported() else "fp16"
    if value == "bf16" and not torch.cuda.is_bf16_supported():
        raise ValueError("bf16 was requested but the selected CUDA device does not support it")
    return value


def autocast_context(device: torch.device, precision: str):
    if device.type != "cuda" or precision == "fp32":
        return nullcontext()
    dtype = torch.float16 if precision == "fp16" else torch.bfloat16
    return torch.autocast(device_type="cuda", dtype=dtype)


def create_grad_scaler(device: torch.device, precision: str):
    return torch.amp.GradScaler("cuda", enabled=device.type == "cuda" and precision == "fp16")


def hardware_report(device: torch.device, precision: str) -> dict[str, Any]:
    report: dict[str, Any] = {
        "torch_version": torch.__version__,
        "device": str(device),
        "device_type": device.type,
        "precision": precision,
        "cuda_available": torch.cuda.is_available(),
        "cuda_runtime": torch.version.cuda,
    }
    if device.type == "cuda":
        properties = torch.cuda.get_device_properties(device)
        report.update({
            "device_name": properties.name,
            "device_total_memory_bytes": properties.total_memory,
            "device_capability": list(torch.cuda.get_device_capability(device)),
        })
    else:
        report["device_name"] = "CPU"
    return report
