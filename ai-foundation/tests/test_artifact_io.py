import json
import sys
import tempfile
import unittest
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from artifact_io import atomic_torch_save, atomic_write_json


class AtomicArtifactIOTests(unittest.TestCase):
    def test_torch_checkpoint_is_written_and_loadable(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "nested" / "checkpoint.pt"
            returned = atomic_torch_save({"step": 12, "weights": torch.tensor([1, 2])}, target)
            self.assertEqual(returned, target)
            loaded = torch.load(target, map_location="cpu", weights_only=True)
            self.assertEqual(loaded["step"], 12)
            torch.testing.assert_close(loaded["weights"], torch.tensor([1, 2]))
            self.assertFalse(target.with_name(target.name + ".tmp").exists())

    def test_failed_torch_serialization_preserves_previous_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "checkpoint.pt"
            target.write_bytes(b"previous-valid-artifact")
            with self.assertRaises(Exception):
                atomic_torch_save({"unserializable": lambda value: value}, target)
            self.assertEqual(target.read_bytes(), b"previous-valid-artifact")
            self.assertFalse(target.with_name(target.name + ".tmp").exists())

    def test_json_metadata_is_utf8_and_atomically_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "metadata.json"
            target.write_text('{"old": true}', encoding="utf-8")
            atomic_write_json({"language": "नेपाली", "step": 25}, target)
            self.assertEqual(json.loads(target.read_text(encoding="utf-8")),
                             {"language": "नेपाली", "step": 25})
            self.assertFalse(target.with_name(target.name + ".tmp").exists())

    def test_invalid_json_number_preserves_previous_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "metadata.json"
            target.write_text('{"valid": true}', encoding="utf-8")
            with self.assertRaises(ValueError):
                atomic_write_json({"invalid": float("nan")}, target)
            self.assertEqual(target.read_text(encoding="utf-8"), '{"valid": true}')
            self.assertFalse(target.with_name(target.name + ".tmp").exists())


if __name__ == "__main__":
    unittest.main()
