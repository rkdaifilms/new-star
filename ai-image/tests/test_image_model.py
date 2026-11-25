from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
import torch
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from data import ImageFolderDataset, audit_image_dataset
from generate import load_checkpoint, save_image_grid
from model import DDPMSchedule, NewStarImageUNet
from train import main as train_main


def create_dataset(root: Path, count: int = 2) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    for index in range(count):
        image = Image.new("RGB", (18 + index, 20 + index), (index * 50, 100, 180))
        image.save(root / f"sample-{index}.png")
    return root


def create_manifest(path: Path) -> Path:
    path.write_text(json.dumps({
        "dataset_name": "New Star automated image fixtures",
        "source": "generated test fixtures",
        "license": "CC0-1.0",
        "permission_basis": "created by the test suite",
    }), encoding="utf-8")
    return path


def test_unet_predicts_noise_with_matching_shape():
    model = NewStarImageUNet(base_channels=8)
    images = torch.randn(2, 3, 32, 32)
    times = torch.tensor([0, 3], dtype=torch.long)
    prediction = model(images, times)
    assert prediction.shape == images.shape
    assert torch.isfinite(prediction).all()


def test_unet_rejects_invalid_spatial_dimensions():
    model = NewStarImageUNet(base_channels=8)
    with pytest.raises(ValueError, match="divisible by 4"):
        model(torch.randn(1, 3, 30, 30), torch.tensor([1]))


def test_diffusion_noise_schedule_and_seeded_sampling_are_reproducible():
    schedule = DDPMSchedule(steps=3)
    clean = torch.zeros(2, 3, 8, 8)
    noise = torch.ones_like(clean)
    noisy = schedule.add_noise(clean, torch.tensor([0, 2]), noise)
    assert noisy.shape == clean.shape
    model = NewStarImageUNet(base_channels=8)
    first = schedule.sample(model, 1, 8, "cpu", seed=77)
    second = schedule.sample(model, 1, 8, "cpu", seed=77)
    torch.testing.assert_close(first, second)


def test_image_dataset_loads_rgb_tensors_and_audits_provenance(tmp_path):
    root = create_dataset(tmp_path / "images")
    manifest = create_manifest(tmp_path / "manifest.json")
    audit = audit_image_dataset(root, manifest)
    assert audit["ok"] is True
    assert audit["image_count"] == 2
    dataset = ImageFolderDataset(root, image_size=32)
    tensor = dataset[0]
    assert tensor.shape == (3, 32, 32)
    assert tensor.min() >= -1 and tensor.max() <= 1


def test_image_dataset_rejects_missing_provenance_and_empty_data(tmp_path):
    root = tmp_path / "empty"
    root.mkdir()
    with pytest.raises(ValueError, match="no supported image"):
        ImageFolderDataset(root, 32)
    with pytest.raises(ValueError, match="manifest"):
        audit_image_dataset(root, tmp_path / "missing.json")


def test_tiny_training_run_saves_reloadable_checkpoint_and_generates_png(tmp_path, monkeypatch):
    root = create_dataset(tmp_path / "images")
    manifest = create_manifest(tmp_path / "manifest.json")
    checkpoint = tmp_path / "image-model.pt"
    monkeypatch.setattr(sys, "argv", [
        "train.py", "--data", str(root), "--manifest", str(manifest),
        "--output", str(checkpoint), "--steps", "1", "--batch-size", "1",
        "--image-size", "32", "--base-channels", "8",
        "--diffusion-steps", "2", "--log-interval", "1",
    ])
    train_main()
    model, schedule, config = load_checkpoint(checkpoint, "cpu")
    assert config["image_size"] == 32
    generated = schedule.sample(model, 1, 32, "cpu", seed=9)
    output = tmp_path / "generated.png"
    save_image_grid(generated, output)
    with Image.open(output) as image:
        assert image.size == (32, 32)
        assert image.format == "PNG"


def test_image_manifest_requires_permission_provenance(tmp_path):
    root = create_dataset(tmp_path / "images", count=1)
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({
        "dataset_name": "test", "source": "test", "license": "unknown",
    }), encoding="utf-8")
    with pytest.raises(ValueError, match="permission_basis"):
        audit_image_dataset(root, manifest)
