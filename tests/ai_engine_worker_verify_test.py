from __future__ import annotations

import base64
import importlib.util
import io
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).resolve().parents[1] / "ai-engine" / "self-hosted" / "verify-workers.py"
SPEC = importlib.util.spec_from_file_location("new_star_verify_workers", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load New Star worker verification module")
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


class WorkerConfigurationTests(unittest.TestCase):
    def test_rejects_missing_endpoint_or_key(self):
        with self.assertRaisesRegex(ValueError, "not set"):
            verify.validate_config("image", "", "x" * 40)
        with self.assertRaisesRegex(ValueError, "not set"):
            verify.validate_config("image", "https://private.example", "")

    def test_rejects_short_or_placeholder_key(self):
        with self.assertRaisesRegex(ValueError, "at least 32"):
            verify.validate_config("image", "https://private.example", "short")
        with self.assertRaisesRegex(ValueError, "at least 32"):
            verify.validate_config("image", "https://private.example", "replace-" + "x" * 40)

    def test_rejects_non_https_and_embedded_credentials(self):
        key = "n" * 40
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            verify.validate_config("image", "http://private.example", key)
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            verify.validate_config("image", "https://user:pass@private.example", key)

    def test_normalizes_valid_https_base_url(self):
        self.assertEqual(
            verify.validate_config("image", "https://private.example/prefix/", "n" * 40),
            "https://private.example/prefix",
        )


class WorkerAuthenticationTests(unittest.TestCase):
    def setUp(self):
        verify.failures.clear()
        verify.passed = 0
        verify.skipped = 0

    def test_health_and_invalid_bearer_rejection_are_required(self):
        output = io.StringIO()
        with patch.object(
            verify,
            "request_json",
            side_effect=[
                (200, {"ok": True, "cuda": True}, ""),
                (401, {"detail": "Unauthorized"}, ""),
            ],
        ) as request, redirect_stdout(output):
            result = verify.health_check("image", "https://private.example", "s" * 40, "/v1/images/generations")

        self.assertEqual(result, ("https://private.example", "s" * 40))
        self.assertEqual(request.call_count, 2)
        self.assertEqual(request.call_args.args[0], "https://private.example/v1/images/generations")
        self.assertEqual(request.call_args.args[1], "new-star-invalid-auth-probe")
        self.assertIn("PASS: image:", output.getvalue())
        self.assertNotIn("s" * 40, output.getvalue())

    def test_worker_fails_if_invalid_bearer_is_accepted(self):
        with patch.object(
            verify,
            "request_json",
            side_effect=[
                (200, {"ok": True, "cuda": True}, ""),
                (200, {"ok": True}, ""),
            ],
        ), redirect_stdout(io.StringIO()):
            result = verify.health_check("image", "https://private.example", "s" * 40, "/v1/images/generations")

        self.assertIsNone(result)
        self.assertEqual(verify.failures, ["image"])

    def test_worker_fails_when_health_is_not_ok(self):
        with patch.object(verify, "request_json", return_value=(200, {"ok": False}, "")), redirect_stdout(io.StringIO()):
            result = verify.health_check("search", "https://private.example", "s" * 40, "/v1/search")

        self.assertIsNone(result)
        self.assertEqual(verify.failures, ["search"])


class VisionFixtureTests(unittest.TestCase):
    def test_vision_fixture_is_valid_base64_png_and_meets_api_minimum(self):
        encoded = verify.PNG_1X1
        raw = base64.b64decode(encoded, validate=True)
        self.assertGreaterEqual(len(encoded), 100)
        self.assertTrue(raw.startswith(b"\\x89PNG\\r\\n\\x1a\\n"))


if __name__ == "__main__":
    unittest.main()
