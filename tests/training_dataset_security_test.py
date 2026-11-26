from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "ai-engine" / "training" / "prepare_dataset.py"
SPEC = importlib.util.spec_from_file_location("new_star_prepare_dataset", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load New Star training dataset validator")
prepare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prepare)


def conversation(content: str = "Hello, can you help?"):
    return {"messages": [
        {"role": "user", "content": content},
        {"role": "assistant", "content": "Yes, I can help."},
    ]}


class DatasetValidationTests(unittest.TestCase):
    def test_accepts_valid_conversation_and_normalizes_whitespace(self):
        record = {"messages": [
            {"role": "user", "content": "  Hello  "},
            {"role": "assistant", "content": "  Hi there  "},
        ]}
        self.assertEqual(prepare.clean_record(record, 1)["messages"][0]["content"], "Hello")
        self.assertEqual(prepare.clean_record(record, 1)["messages"][1]["content"], "Hi there")

    def test_rejects_conversation_without_user_or_final_assistant(self):
        with self.assertRaisesRegex(ValueError, "end with assistant"):
            prepare.clean_record({"messages": [{"role": "assistant", "content": "Only assistant"}]}, 2)

    def test_rejects_invalid_role(self):
        with self.assertRaisesRegex(ValueError, "invalid message role"):
            prepare.clean_record({"messages": [
                {"role": "system", "content": "You are helpful."},
                {"role": "tool", "content": "result"},
            ]}, 3)

    def test_rejects_oversized_message(self):
        with self.assertRaisesRegex(ValueError, "12,000"):
            prepare.clean_record(conversation("x" * 12001), 4)

    def test_rejects_openai_style_api_key(self):
        with self.assertRaisesRegex(ValueError, "credential/secret"):
            prepare.clean_record(conversation("token sk-" + "a" * 28), 5)

    def test_rejects_aws_access_key(self):
        with self.assertRaisesRegex(ValueError, "credential/secret"):
            prepare.clean_record(conversation("AWS key AKIA1234567890ABCDEF"), 6)

    def test_rejects_private_key_pem_header(self):
        with self.assertRaisesRegex(ValueError, "credential/secret"):
            prepare.clean_record(conversation("-----BEGIN PRIVATE KEY-----"), 7)

    def test_rejects_bearer_token(self):
        with self.assertRaisesRegex(ValueError, "credential/secret"):
            prepare.clean_record(conversation("Bearer " + "a" * 30), 8)

    def test_rejects_password_assignment(self):
        with self.assertRaisesRegex(ValueError, "credential/secret"):
            prepare.clean_record(conversation("password=verysecretvalue123"), 9)


if __name__ == "__main__":
    unittest.main()
