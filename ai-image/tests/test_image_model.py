from __future__ import annotations

import base64
import json
import sys
import threading
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from pathlib import Path

import pytest
import torch
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from data import ImageFolderDataset, audit_image_dataset
from generate import load_checkpoint, save_image_grid
from model import DDPMSchedule, NewStarImageUNet
from serve import create_handler
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


def test_image_http_api_generates_png_and_rejects_text_prompt():
    model = NewStarImageUNet(base_channels=8).eval()
    schedule = DDPMSchedule(steps=2)
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0),
        create_handler(model, schedule, 8, "cpu", "image-test-secret"),
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    try:
        request = Request(
            base + "/v1/images/generations",
            data=json.dumps({"n": 1, "seed": 123, "size": "8x8"}).encode(),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer image-test-secret",
            },
        )
        with urlopen(request) as response:
            result = json.loads(response.read())
        png = base64.b64decode(result["data"][0]["b64_json"])
        assert png[:8] == bytes((137, 80, 78, 71, 13, 10, 26, 10))
        prompt_request = Request(
            base + "/v1/images/generations",
            data=json.dumps({"prompt": "a mountain"}).encode(),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer image-test-secret",
            },
        )
        with pytest.raises(HTTPError) as error:
            urlopen(prompt_request)
        assert error.value.code == 400
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_image_http_api_requires_authentication():
    model = NewStarImageUNet(base_channels=8).eval()
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0),
        create_handler(model, DDPMSchedule(steps=2), 8, "cpu", "required-secret"),
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        request = Request(
            f"http://127.0.0.1:{server.server_address[1]}/v1/images/generations",
            data=json.dumps({"n": 1}).encode(),
            headers={"Content-Type": "application/json"},
        )
        with pytest.raises(HTTPError) as error:
            urlopen(request)
        assert error.value.code == 401
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_diffusion_schedule_rejects_out_of_range_timesteps_and_mismatched_noise():
    schedule = DDPMSchedule(steps=3)
    clean = torch.zeros(1, 3, 8, 8)
    with pytest.raises(ValueError, match="within the diffusion schedule"):
        schedule.add_noise(clean, torch.tensor([3]), torch.ones_like(clean))
    with pytest.raises(ValueError, match="share"):
        schedule.add_noise(clean, torch.tensor([1]), torch.ones(1, 3, 4, 4))


def test_image_http_api_rejects_unsupported_fields():
    model = NewStarImageUNet(base_channels=8).eval()
    server = ThreadingHTTPServer(
        ("127.0.0.1", 0),
        create_handler(model, DDPMSchedule(steps=2), 8, "cpu", "field-secret"),
    )
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        request = Request(
            f"http://127.0.0.1:{server.server_address[1]}/v1/images/generations",
            data=json.dumps({"n": 1, "quality": "high"}).encode(),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer field-secret",
            },
        )
        with pytest.raises(HTTPError) as error:
            urlopen(request)
        assert error.value.code == 400
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
