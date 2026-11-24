"""Integration tests for the local HTTP inference API and upstream adapters."""

from __future__ import annotations

import json
import os
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from ai_engine.config import EngineSettings
from ai_engine.inference import ChatInference
from ai_engine.registry import ModelRegistry, ModelSpec
from ai_engine.server import make_handler, validate_chat_request


class FakeUpstreamHandler(BaseHTTPRequestHandler):
    protocol_mode = "openai_chat"
    fail = False

    def log_message(self, format: str, *args: object) -> None:
        return

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length))
        if type(self).fail and self.path.endswith("/v1/chat/completions"):
            self.send_error(503, "primary model temporarily unavailable")
            return
        if self.path.endswith("/v1/chat/completions"):
            if body.get("stream"):
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                for piece in ("Hello", " New Star"):
                    event = {"choices": [{"delta": {"content": piece}}]}
                    self.wfile.write(("data: " + json.dumps(event) + "\n\n").encode())
                    self.wfile.flush()
                self.wfile.write(b"data: [DONE]\n\n")
            else:
                payload = {
                    "id": "upstream-id",
                    "created": 1700000000,
                    "choices": [{"message": {"role": "assistant", "content": "Hello New Star"},
                                 "finish_reason": "stop"}],
                    "usage": {"prompt_tokens": 4, "completion_tokens": 3, "total_tokens": 7},
                }
                encoded = json.dumps(payload).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)
            return
        if self.path == "/api/chat":
            self.send_response(200)
            self.send_header("Content-Type", "application/x-ndjson")
            self.end_headers()
            if body.get("stream"):
                for item in ({"message": {"content": "Ollama "}, "done": False},
                             {"message": {"content": "works"}, "done": True}):
                    self.wfile.write((json.dumps(item) + "\n").encode())
                    self.wfile.flush()
            else:
                encoded = json.dumps({"message": {"content": "Ollama works"}, "done": True}).encode()
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)
            return
        if self.path == "/generate":
            encoded = json.dumps({"output": "Custom adapter works"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
            return
        self.send_error(404)

    def do_GET(self) -> None:
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"{}")


class InferenceAPITests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.upstream = ThreadingHTTPServer(("127.0.0.1", 0), FakeUpstreamHandler)
        cls.thread = threading.Thread(target=cls.upstream.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.upstream.server_address[1]}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.upstream.shutdown()
        cls.upstream.server_close()
        cls.thread.join(timeout=2)

    def setUp(self) -> None:
        FakeUpstreamHandler.fail = False
        self.settings = EngineSettings(models_file=Path("unused"), failure_threshold=1,
                                       circuit_cooldown_seconds=1, max_circuit_cooldown_seconds=2)
        self.openai = ModelSpec(
            model_id="openai-local", provider="fake-openai", endpoint_url=self.base_url,
            protocol="openai_chat", model_name="fake", capabilities=frozenset({"chat"}),
            priority=1, timeout_seconds=2,
        )
        self.ollama = ModelSpec(
            model_id="ollama-local", provider="fake-ollama", endpoint_url=self.base_url,
            protocol="ollama_chat", model_name="fake-ollama", capabilities=frozenset({"chat"}),
            priority=2, timeout_seconds=2,
        )
        self.registry = ModelRegistry(self.settings, [self.openai, self.ollama])
        self.inference = ChatInference(self.registry)

    def test_openai_compatible_completion_response(self) -> None:
        response = self.inference.complete({"model": "auto", "messages": [{"role": "user", "content": "Hi"}]})
        self.assertEqual(response["object"], "chat.completion")
        self.assertEqual(response["choices"][0]["message"]["content"], "Hello New Star")
        self.assertEqual(response["usage"]["total_tokens"], 7)

    def test_custom_json_adapter_completion(self) -> None:
        custom = ModelSpec(
            model_id="custom-local", provider="custom", endpoint_url=self.base_url,
            protocol="custom_json", model_name="custom-model", capabilities=frozenset({"chat"}),
            timeout_seconds=2,
        )
        response = ChatInference(ModelRegistry(self.settings, [custom])).complete({
            "model": "auto", "messages": [{"role": "user", "content": "Hi"}]
        })
        self.assertEqual(response["choices"][0]["message"]["content"], "Custom adapter works")

    def test_ollama_adapter_completion(self) -> None:
        self.registry = ModelRegistry(self.settings, [self.ollama])
        response = ChatInference(self.registry).complete({
            "model": "auto", "messages": [{"role": "user", "content": "Hi"}]
        })
        self.assertEqual(response["choices"][0]["message"]["content"], "Ollama works")

    def test_streaming_adapter_yields_text(self) -> None:
        _, model_id, chunks = self.inference.stream({
            "model": "openai-local", "messages": [{"role": "user", "content": "Hi"}]
        })
        self.assertEqual(model_id, "openai-local")
        self.assertEqual("".join(chunks), "Hello New Star")

    def test_fallback_uses_ollama_after_primary_http_failure(self) -> None:
        FakeUpstreamHandler.fail = True
        response = self.inference.complete({"model": "openai-local",
                                            "messages": [{"role": "user", "content": "Hi"}]})
        self.assertEqual(response["model"], "ollama-local")
        self.assertEqual(response["choices"][0]["message"]["content"], "Ollama works")

    def test_chat_request_validation_rejects_invalid_stream_flag(self) -> None:
        with self.assertRaises(ValueError):
            validate_chat_request({"messages": [{"role": "user", "content": "Hi"}], "stream": "yes"})

    def test_http_server_exposes_chat_endpoint_and_health(self) -> None:
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.inference))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = f"http://127.0.0.1:{server.server_address[1]}"
            with urlopen(base + "/health") as response:
                self.assertEqual(json.loads(response.read())["status"], "ok")
            body = json.dumps({"messages": [{"role": "user", "content": "Hi"}]}).encode()
            req = Request(base + "/v1/chat/completions", data=body,
                          headers={"Content-Type": "application/json"})
            with urlopen(req) as response:
                payload = json.loads(response.read())
            self.assertEqual(payload["object"], "chat.completion")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_http_server_streams_sse_chunks(self) -> None:
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.inference))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            body = json.dumps({"stream": True, "model": "openai-local",
                               "messages": [{"role": "user", "content": "Hi"}]}).encode()
            req = Request(f"http://127.0.0.1:{server.server_address[1]}/v1/chat/completions",
                          data=body, headers={"Content-Type": "application/json"})
            with urlopen(req) as response:
                payload = response.read().decode()
                self.assertEqual(response.headers.get_content_type(), "text/event-stream")
            self.assertIn("Hello", payload)
            self.assertIn("New Star", payload)
            self.assertIn("data: [DONE]", payload)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_http_server_requires_bearer_when_token_configured(self) -> None:
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.inference, "secret-token"))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/health"
            with self.assertRaises(HTTPError) as caught:
                urlopen(url)
            self.assertEqual(caught.exception.code, 401)
            req = Request(url, headers={"Authorization": "Bearer secret-token"})
            with urlopen(req) as response:
                self.assertEqual(response.status, 200)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
