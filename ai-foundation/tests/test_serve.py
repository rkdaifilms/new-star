import json
import sys
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from serve import create_handler, format_chat_messages


class FakeConfig:
    block_size = 128


class FakeModel:
    config = FakeConfig()

    def generate(self, input_ids, max_tokens, temperature, top_k, top_p, repetition_penalty):
        import torch
        return torch.cat(
            [input_ids, torch.tensor([[65]], dtype=torch.long, device=input_ids.device)],
            dim=1,
        )


class FakeTokenizer:
    def encode(self, text):
        return [ord(char) % 256 for char in text]

    def decode(self, token_ids):
        return "".join(chr(token_id) for token_id in token_ids)


class FoundationServerTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(
            ("127.0.0.1", 0),
            create_handler(FakeModel(), FakeTokenizer(), "test-model", "cpu", "test-secret"),
        )
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def get(self, path):
        request = Request(self.base + path, method="GET")
        return urlopen(request)

    def test_model_list_is_openai_compatible(self):
        with self.get("/v1/models") as response:
            result = json.loads(response.read())
        self.assertEqual(result["object"], "list")
        self.assertEqual(result["data"][0]["id"], "test-model")
        self.assertEqual(result["data"][0]["owned_by"], "new-star")

    def post(self, payload, content_type="application/json"):
        request = Request(
            self.base + "/v1/chat/completions",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": content_type,
                "Authorization": "Bearer test-secret",
            },
        )
        return urlopen(request)

    def test_chat_completion_returns_openai_compatible_shape(self):
        with self.post({
            "messages": [{"role": "user", "content": "नमस्ते"}],
            "max_tokens": 1,
        }) as response:
            result = json.loads(response.read())
        self.assertEqual(result["object"], "chat.completion")
        self.assertEqual(result["model"], "test-model")
        self.assertEqual(result["choices"][0]["message"]["role"], "assistant")
        self.assertEqual(result["usage"]["completion_tokens"], 1)

    def test_rejects_non_json_content_type(self):
        with self.assertRaises(HTTPError) as error:
            self.post(
                {"messages": [{"role": "user", "content": "hello"}]},
                content_type="text/plain",
            )
        self.assertEqual(error.exception.code, 400)

    def test_rejects_boolean_and_fractional_max_tokens(self):
        for value in (True, 1.5):
            with self.subTest(value=value):
                with self.assertRaises(HTTPError) as error:
                    self.post({
                        "messages": [{"role": "user", "content": "hello"}],
                        "max_tokens": value,
                    })
                self.assertEqual(error.exception.code, 400)

    def test_rejects_unsupported_streaming(self):
        with self.assertRaises(HTTPError) as error:
            self.post({
                "messages": [{"role": "user", "content": "hello"}],
                "stream": True,
            })
        self.assertEqual(error.exception.code, 400)

    def test_chat_message_format_requires_user_turn_last(self):
        with self.assertRaisesRegex(ValueError, "final chat message"):
            format_chat_messages([
                {"role": "user", "content": "hello"},
                {"role": "assistant", "content": "answer"},
            ])


if __name__ == "__main__":
    unittest.main()
