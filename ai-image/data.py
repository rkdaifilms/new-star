"""Image-folder loading and dataset provenance checks for New Star image training."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import torch
from PIL import Image
from torch.utils.data import Dataset

from text_conditioning import encode_caption, MAX_TEXT_TOKENS


IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}


def audit_image_dataset(directory: str | Path, manifest_path: str | Path) -> dict:
    root = Path(directory).resolve()
    manifest_file = Path(manifest_path).resolve()
    if not root.is_dir():
        raise ValueError("image dataset directory does not exist")
    if not manifest_file.is_file():
        raise ValueError("image dataset provenance manifest does not exist")
    try:
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("image dataset manifest must be valid UTF-8 JSON") from exc
    required = ("dataset_name", "source", "license", "permission_basis")
    for field in required:
        if not isinstance(manifest.get(field), str) or not manifest[field].strip():
            raise ValueError(f"manifest field {field!r} must be a non-empty string")
    paths = sorted(
        path for path in root.rglob("*")
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
    )
    if not paths:
        raise ValueError("image dataset contains no supported image files")
    records = []
    total_bytes = 0
    for path in paths:
        if path.is_symlink():
            raise ValueError("image dataset must not contain symbolic links")
        try:
            with Image.open(path) as image:
                image.verify()
        except Exception as exc:
            raise ValueError(f"invalid image file: {path.name}") from exc
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        size = path.stat().st_size
        total_bytes += size
        records.append({
            "file": path.relative_to(root).as_posix(),
            "sha256": digest,
            "bytes": size,
        })
    manifest_digest = hashlib.sha256(
        json.dumps(manifest, sort_keys=True, ensure_ascii=False).encode("utf-8")
    ).hexdigest()
    captions = manifest.get("captions")
    if captions is not None:
        if not isinstance(captions, dict):
            raise ValueError("manifest captions must be an object mapping relative image paths to text")
        expected_files = {record["file"] for record in records}
        if set(captions) != expected_files:
            raise ValueError("manifest captions must contain exactly one caption for every image file")
        for filename, caption in captions.items():
            try:
                encode_caption(caption)
            except ValueError as exc:
                raise ValueError(f"invalid caption for image {filename!r}: {exc}") from exc
    return {
        "ok": True,
        "dataset_name": manifest["dataset_name"],
        "source": manifest["source"],
        "license": manifest["license"],
        "permission_basis": manifest["permission_basis"],
        "manifest_sha256": manifest_digest,
        "image_count": len(records),
        "total_bytes": total_bytes,
        "images": records,
        "caption_count": len(captions) if captions is not None else 0,
    }


class ImageFolderDataset(Dataset):
    def __init__(self, directory: str | Path, image_size: int, captions: dict[str, str] | None = None):
        if not isinstance(image_size, int) or image_size < 8 or image_size % 4:
            raise ValueError("image_size must be an integer >= 8 and divisible by 4")
        self.root = Path(directory).resolve()
        self.paths = sorted(
            path for path in self.root.rglob("*")
            if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
        )
        if not self.paths:
            raise ValueError("image dataset contains no supported images")
        self.image_size = image_size
        self.captions = captions
        if captions is not None:
            if not isinstance(captions, dict):
                raise ValueError("captions must be a mapping of relative image paths to text")
            expected = {path.relative_to(self.root).as_posix() for path in self.paths}
            if set(captions) != expected:
                raise ValueError("captions must contain exactly one entry for every dataset image")
            for name, caption in captions.items():
                try:
                    encode_caption(caption)
                except ValueError as exc:
                    raise ValueError(f"invalid caption for image {name!r}: {exc}") from exc

    def __len__(self) -> int:
        return len(self.paths)

    def __getitem__(self, index: int) -> torch.Tensor:
        path = self.paths[index]
        with Image.open(path) as source:
            image = source.convert("RGB").resize(
                (self.image_size, self.image_size), Image.Resampling.BICUBIC
            )
            raw = bytearray(image.tobytes())
        tensor = torch.frombuffer(raw, dtype=torch.uint8).reshape(
            self.image_size, self.image_size, 3
        )
        image_tensor = tensor.permute(2, 0, 1).float().div(127.5).sub(1.0)
        if self.captions is None:
            return image_tensor
        from text_conditioning import encode_caption
        token_ids = torch.zeros(MAX_TEXT_TOKENS, dtype=torch.long)
        encoded = encode_caption(self.captions[path.relative_to(self.root).as_posix()])
        token_ids[:len(encoded)] = torch.tensor(encoded, dtype=torch.long)
        return image_tensor, token_ids
