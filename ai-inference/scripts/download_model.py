"""Download and verify the pinned New Star bootstrap GGUF model.

The model weights stay outside Git. The SHA-256 pin prevents silently loading a
changed or incomplete download. Override MODEL_URL / MODEL_SHA256 only when
intentionally selecting and reviewing a different model.
"""
from __future__ import annotations

import hashlib
import os
import sys
import urllib.request
from pathlib import Path

DEFAULT_MODEL_URL = (
    "https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF/resolve/main/"
    "Qwen3-0.6B-Q4_0.gguf"
)
DEFAULT_MODEL_SHA256 = "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as model_file:
        for chunk in iter(lambda: model_file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_model(
    destination: Path,
    url: str = DEFAULT_MODEL_URL,
    expected_sha256: str = DEFAULT_MODEL_SHA256,
) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and sha256_file(destination) == expected_sha256:
        print(f"Verified existing model: {destination.name}", flush=True)
        return destination

    if destination.exists():
        destination.unlink()

    partial = destination.with_name(destination.name + ".part")
    if partial.exists():
        partial.unlink()

    request = urllib.request.Request(
        url,
        headers={"User-Agent": "NewStar-Model-Bootstrap/1.0"},
    )
    digest = hashlib.sha256()
    try:
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as output:
            total = response.headers.get("Content-Length")
            expected_size = int(total) if total and total.isdigit() else None
            downloaded = 0
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                output.write(chunk)
                digest.update(chunk)
                downloaded += len(chunk)
                if expected_size:
                    print(f"Model download: {downloaded * 100 // expected_size}%", flush=True)
            output.flush()
            os.fsync(output.fileno())

        actual = digest.hexdigest()
        if actual != expected_sha256:
            raise RuntimeError("Downloaded model SHA-256 did not match the pinned checksum.")
        partial.replace(destination)
        print(f"Model downloaded and verified: {destination.name}", flush=True)
        return destination
    except Exception:
        partial.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    target = Path(os.getenv("MODEL_PATH", "/models/Qwen3-0.6B-Q4_0.gguf"))
    model_url = os.getenv("MODEL_URL", DEFAULT_MODEL_URL)
    checksum = os.getenv("MODEL_SHA256", DEFAULT_MODEL_SHA256).lower()
    if len(checksum) != 64 or any(char not in "0123456789abcdef" for char in checksum):
        raise SystemExit("MODEL_SHA256 must be a 64-character hexadecimal SHA-256 value.")
    try:
        ensure_model(target, model_url, checksum)
    except Exception as exc:
        print(f"Model bootstrap failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
