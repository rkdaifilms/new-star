import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ai_engine.security import TokenBucketLimiter, authenticate_api_key, load_api_keys, UsageLogger

class SecurityTests(unittest.TestCase):
    def test_api_key_registry_and_authentication(self):
        keys = load_api_keys("mobile:sample-secret,web:another-secret")
        self.assertEqual(authenticate_api_key("Bearer sample-secret", keys), "mobile")
        self.assertIsNone(authenticate_api_key("Bearer wrong", keys))
        self.assertIsNone(authenticate_api_key("Basic sample-secret", keys))

    def test_rejects_duplicate_or_malformed_key_registry(self):
        for raw in ("mobile:one,mobile:two", "missing-separator", "mobile:"):
            with self.assertRaises(ValueError):
                load_api_keys(raw)

    def test_token_bucket_limits_and_refills(self):
        limiter = TokenBucketLimiter(capacity=2, refill_per_second=1)
        self.assertTrue(limiter.allow("client", now=10))
        self.assertTrue(limiter.allow("client", now=10))
        self.assertFalse(limiter.allow("client", now=10))
        self.assertTrue(limiter.allow("client", now=11))

    def test_usage_log_does_not_accept_prompt_fields(self):
        import tempfile, json
        with tempfile.TemporaryDirectory() as folder:
            path = str(Path(folder) / "usage.jsonl")
            logger = UsageLogger(path)
            logger.record("req-1", "mobile", "/v1/new-star/chat", 200, 12, "local-model")
            record = json.loads(Path(path).read_text())
            self.assertEqual(record["client_id"], "mobile")
            self.assertNotIn("prompt", record)
            self.assertNotIn("completion", record)
            self.assertNotIn("secret", record)

if __name__ == "__main__":
    unittest.main()
