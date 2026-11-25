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
from evaluate import atomic_write_json, evaluate as evaluate_checkpoint
from model import DDPMSchedule, NewStarImageUNet
from serve import create_handler
from train import main as train_main


def create_dataset(root: Path, count: int = 2, color_offset: int = 0) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    for index in range(count):
        image = Image.new("RGB", (18 + index, 20 + index), (index * 50 + color_offset, 100, 180))
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
    saved = torch.load(checkpoint, map_location="cpu", weights_only=True)
    for key, value in model.state_dict().items():
        torch.testing.assert_close(value, saved["ema_state_dict"][key], rtol=0, atol=0)
    assert saved["training"]["ema_decay"] == 0.999
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


def test_image_training_resume_matches_uninterrupted_cpu_run(tmp_path, monkeypatch):
    root = create_dataset(tmp_path / "images")
    manifest = create_manifest(tmp_path / "manifest.json")
    uninterrupted = tmp_path / "uninterrupted.pt"
    resumed = tmp_path / "resumed.pt"

    common = [
        "--data", str(root), "--manifest", str(manifest),
        "--batch-size", "1", "--image-size", "32",
        "--base-channels", "8", "--diffusion-steps", "2",
        "--learning-rate", "0.0002", "--seed", "41",
        "--log-interval", "1", "--checkpoint-interval", "1",
        "--device", "cpu",
    ]

    monkeypatch.setattr(sys, "argv", [
        "train.py", *common, "--output", str(uninterrupted), "--steps", "2",
    ])
    train_main()

    monkeypatch.setattr(sys, "argv", [
        "train.py", *common, "--output", str(resumed), "--steps", "1",
    ])
    train_main()
    resume_file = Path(str(resumed) + ".resume.pt")
    assert resume_file.is_file()

    monkeypatch.setattr(sys, "argv", [
        "train.py", *common, "--output", str(resumed), "--steps", "2",
        "--resume-from", str(resume_file),
    ])
    train_main()

    expected = torch.load(uninterrupted, map_location="cpu", weights_only=True)
    actual = torch.load(resumed, map_location="cpu", weights_only=True)
    assert actual["training"]["steps"] == 2
    assert actual["dataset_fingerprint"] == expected["dataset_fingerprint"]
    assert actual["state_dict"].keys() == expected["state_dict"].keys()
    for key in expected["state_dict"]:
        torch.testing.assert_close(actual["state_dict"][key], expected["state_dict"][key], rtol=0, atol=0)
    assert actual["ema_state_dict"].keys() == expected["ema_state_dict"].keys()
    for key in expected["ema_state_dict"]:
        torch.testing.assert_close(actual["ema_state_dict"][key], expected["ema_state_dict"][key], rtol=0, atol=0)


def test_image_training_resume_rejects_changed_dataset(tmp_path, monkeypatch):
    root = create_dataset(tmp_path / "images")
    manifest = create_manifest(tmp_path / "manifest.json")
    output = tmp_path / "model.pt"
    common = [
        "--data", str(root), "--manifest", str(manifest),
        "--output", str(output), "--steps", "1", "--batch-size", "1",
        "--image-size", "32", "--base-channels", "8",
        "--diffusion-steps", "2", "--checkpoint-interval", "1",
    ]
    monkeypatch.setattr(sys, "argv", ["train.py", *common])
    train_main()
    Image.new("RGB", (22, 22), (20, 30, 40)).save(root / "changed.png")
    monkeypatch.setattr(sys, "argv", [
        "train.py", *common, "--steps", "2",
        "--resume-from", str(output) + ".resume.pt",
    ])
    with pytest.raises(SystemExit):
        train_main()



def test_image_training_rejects_invalid_ema_decay(tmp_path, monkeypatch):
    root = create_dataset(tmp_path / "images")
    manifest = create_manifest(tmp_path / "manifest.json")
    monkeypatch.setattr(sys, "argv", [
        "train.py", "--data", str(root), "--manifest", str(manifest),
        "--steps", "1", "--ema-decay", "1.0",
    ])
    with pytest.raises(SystemExit):
        train_main()


def test_image_training_resume_rejects_changed_permission_manifest(tmp_path, monkeypatch):
    root = create_dataset(tmp_path / "images")
    manifest = create_manifest(tmp_path / "manifest.json")
    output = tmp_path / "model.pt"
    common = [
        "--data", str(root), "--manifest", str(manifest),
        "--output", str(output), "--steps", "1", "--batch-size", "1",
        "--image-size", "32", "--base-channels", "8",
        "--diffusion-steps", "2", "--checkpoint-interval", "1",
    ]
    monkeypatch.setattr(sys, "argv", ["train.py", *common])
    train_main()
    changed_manifest = json.loads(manifest.read_text(encoding="utf-8"))
    changed_manifest["permission_basis"] = "updated permission record"
    manifest.write_text(json.dumps(changed_manifest), encoding="utf-8")
    monkeypatch.setattr(sys, "argv", [
        "train.py", *common, "--steps", "2",
        "--resume-from", str(output) + ".resume.pt",
    ])
    with pytest.raises(SystemExit):
        train_main()



def test_image_checkpoint_evaluation_is_reproducible_and_writes_report(tmp_path, monkeypatch):
    training_root = create_dataset(tmp_path / "training-images")
    training_manifest = create_manifest(tmp_path / "training-manifest.json")
    heldout_root = create_dataset(tmp_path / "heldout-images", count=3, color_offset=7)
    heldout_manifest = create_manifest(tmp_path / "heldout-manifest.json")
    checkpoint = tmp_path / "image-model.pt"
    monkeypatch.setattr(sys, "argv", [
        "train.py", "--data", str(training_root), "--manifest", str(training_manifest),
        "--output", str(checkpoint), "--steps", "1", "--batch-size", "1",
        "--image-size", "32", "--base-channels", "8", "--diffusion-steps", "2",
        "--log-interval", "1",
    ])
    train_main()

    first = evaluate_checkpoint(
        checkpoint, heldout_root, heldout_manifest, "cpu", batch_size=2, seed=72
    )
    second = evaluate_checkpoint(
        checkpoint, heldout_root, heldout_manifest, "cpu", batch_size=2, seed=72
    )
    assert first["weights"] == "ema"
    assert first["evaluation_dataset"]["image_count"] == 3
    assert first["metrics"]["samples_evaluated"] == 3
    assert first["metrics"]["noise_prediction_mse"] == second["metrics"]["noise_prediction_mse"]
    assert first["evaluation_dataset"]["fingerprint"] == second["evaluation_dataset"]["fingerprint"]

    report_path = tmp_path / "reports" / "evaluation.json"
    atomic_write_json(first, report_path)
    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert report["format"] == "new-star-image-evaluation-v1"
    assert report["metrics"]["batches_evaluated"] == 2



def test_image_evaluation_rejects_training_dataset_leakage(tmp_path, monkeypatch):
    root = create_dataset(tmp_path / "images")
    manifest = create_manifest(tmp_path / "manifest.json")
    checkpoint = tmp_path / "image-model.pt"
    monkeypatch.setattr(sys, "argv", [
        "train.py", "--data", str(root), "--manifest", str(manifest),
        "--output", str(checkpoint), "--steps", "1", "--batch-size", "1",
        "--image-size", "32", "--base-channels", "8", "--diffusion-steps", "2",
    ])
    train_main()
    with pytest.raises(ValueError, match="matches the training dataset"):
        evaluate_checkpoint(checkpoint, root, manifest, "cpu", batch_size=1, seed=9)



def test_image_evaluation_rejects_overlapping_images_under_new_filenames(tmp_path, monkeypatch):
    training_root = create_dataset(tmp_path / "training")
    training_manifest = create_manifest(tmp_path / "training-manifest.json")
    checkpoint = tmp_path / "image-model.pt"
    monkeypatch.setattr(sys, "argv", [
        "train.py", "--data", str(training_root), "--manifest", str(training_manifest),
        "--output", str(checkpoint), "--steps", "1", "--batch-size", "1",
        "--image-size", "32", "--base-channels", "8", "--diffusion-steps", "2",
    ])
    train_main()

    heldout_root = tmp_path / "renamed-heldout"
    heldout_root.mkdir()
    (heldout_root / "renamed-sample.png").write_bytes(
        (training_root / "sample-0.png").read_bytes()
    )
    heldout_manifest = tmp_path / "heldout-manifest.json"
    heldout_manifest.write_text(json.dumps({
        "dataset_name": "incorrectly relabeled training image",
        "source": "test fixture",
        "license": "CC0-1.0",
        "permission_basis": "created by test suite",
    }), encoding="utf-8")
    with pytest.raises(ValueError, match="contains image files used for training"):
        evaluate_checkpoint(checkpoint, heldout_root, heldout_manifest, "cpu")


@pytest.mark.parametrize("time_width", [0, -1, True, 1.5, "32"])
def test_unet_rejects_invalid_time_embedding_width(time_width):
    with pytest.raises(ValueError, match="time_width"):
        NewStarImageUNet(base_channels=8, time_width=time_width)


def test_unet_accepts_explicit_positive_time_embedding_width():
    model = NewStarImageUNet(base_channels=8, time_width=12)
    images = torch.randn(1, 3, 32, 32)
    timesteps = torch.tensor([1], dtype=torch.long)
    assert model(images, timesteps).shape == images.shape


@pytest.mark.parametrize("flag,value", [
    ("--learning-rate", "nan"),
    ("--learning-rate", "inf"),
    ("--learning-rate", "-inf"),
    ("--learning-rate", "0"),
    ("--ema-decay", "nan"),
    ("--ema-decay", "inf"),
])
def test_image_training_rejects_non_finite_or_invalid_hyperparameters(flag, value, monkeypatch):
    monkeypatch.setattr(sys, "argv", [
        "train.py", "--data", "unused-images", "--manifest", "unused-manifest.json",
        "--steps", "1", flag, value,
    ])
    with pytest.raises(SystemExit) as error:
        train_main()
    assert error.value.code == 2



def test_image_checkpoint_loader_rejects_invalid_structure_and_weights(tmp_path):
    valid_model = NewStarImageUNet(base_channels=8)
    valid = {
        "format": "new-star-ddpm-image-v1",
        "model_config": {
            "base_channels": 8,
            "image_size": 32,
            "diffusion_steps": 2,
        },
        "state_dict": valid_model.state_dict(),
        "ema_state_dict": valid_model.state_dict(),
        "dataset_audit": {"images": []},
    }
    valid_path = tmp_path / "valid.pt"
    torch.save(valid, valid_path)
    loaded, schedule, config = load_checkpoint(valid_path, "cpu")
    assert config["weights_source"] == "ema"
    assert schedule.steps == 2
    assert loaded.training is False

    malformed = [
        [],
        {**valid, "model_config": []},
        {**valid, "model_config": {**valid["model_config"], "base_channels": True}},
        {**valid, "model_config": {**valid["model_config"], "image_size": 31}},
        {**valid, "model_config": {**valid["model_config"], "diffusion_steps": 1}},
        {**valid, "state_dict": {"unexpected": torch.zeros(1)}},
        {**valid, "ema_state_dict": {"unexpected": torch.zeros(1)}},
        {**valid, "dataset_audit": {"images": [{"sha256": "not-a-digest"}]}},
        {
            **valid,
            "ema_state_dict": {
                **valid["ema_state_dict"],
                "input.weight": torch.full_like(valid["ema_state_dict"]["input.weight"], float("nan")),
            },
        },
    ]
    for index, payload in enumerate(malformed):
        path = tmp_path / f"malformed-{index}.pt"
        torch.save(payload, path)
        with pytest.raises(ValueError):
            load_checkpoint(path, "cpu")


def test_image_checkpoint_loader_rejects_unreadable_file(tmp_path):
    path = tmp_path / "not-a-checkpoint.pt"
    path.write_text("not a torch checkpoint", encoding="utf-8")
    with pytest.raises(ValueError, match="safely load"):
        load_checkpoint(path, "cpu")
