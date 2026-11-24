"""Dependency-free HTTP inference server with OpenAI-compatible chat and SSE."""

from __future__ import annotations

import json
import os
import secrets
import signal
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlsplit

from .config import EngineSettings
from .errors import NewStarAIError, NoEligibleModelError, RoutingExhausted
from .inference import ChatInference
from .registry import ModelRegistry

MAX_BODY_BYTES = 1_048_576
ALLOWED_ROLES = frozenset({"system", "developer", "user", "assistant", "tool"})


def validate_chat_request(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ValueError("request body must be a JSON object")
    messages = payload.get("messages")
    if not isinstance(messages, list) or not messages or len(messages) > 256:
        raise ValueError("messages must be a non-empty array containing at most 256 messages")
    normalized = []
    for index, message in enumerate(messages):
        if not isinstance(message, dict):
            raise ValueError(f"messages[{index}] must be an object")
        role = message.get("role")
        content = message.get("content")
        if role not in ALLOWED_ROLES:
            raise ValueError(f"messages[{index}].role is invalid")
        if not isinstance(content, (str, list)):
            raise ValueError(f"messages[{index}].content must be a string or content-part array")
        if isinstance(content, str) and not content:
            raise ValueError(f"messages[{index}].content must not be empty")
        normalized.append({"role": role, "content": content})
    stream = payload.get("stream", False)
    if not isinstance(stream, bool):
        raise ValueError("stream must be a boolean")
    model = payload.get("model", "auto")
    if not isinstance(model, str) or not model.strip():
        raise ValueError("model must be a non-empty string")
    result: dict[str, Any] = {"model": model, "messages": normalized, "stream": stream}
    for key in ("temperature", "top_p", "presence_penalty", "frequency_penalty"):
        if key in payload:
            value = payload[key]
            if isinstance(value, bool) or not isinstance(value, (int, float)):
                raise ValueError(f"{key} must be numeric")
            if key in ("temperature", "top_p") and not 0 <= value <= 2:
                raise ValueError(f"{key} must be between 0 and 2")
            result[key] = value
    if "max_tokens" in payload:
        value = payload["max_tokens"]
        if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 131072:
            raise ValueError("max_tokens must be an integer between 1 and 131072")
        result["max_tokens"] = value
    if "stop" in payload:
        stop = payload["stop"]
        if not (isinstance(stop, str) or isinstance(stop, list) and
                1 <= len(stop) <= 4 and all(isinstance(item, str) for item in stop)):
            raise ValueError("stop must be a string or an array of 1 to 4 strings")
        result["stop"] = stop
    for key in ("seed", "response_format", "format", "user", "language"):
        if key in payload:
            result[key] = payload[key]
    return result


def make_handler(inference: ChatInference, server_token: str | None = None):
    class InferenceHandler(BaseHTTPRequestHandler):
        server_version = "NewStarAI/0.3"
        protocol_version = "HTTP/1.1"

        def log_message(self, format: str, *args: Any) -> None:
            message = format % args
            print(f"[new-star-ai] {self.client_address[0]} {message}", flush=True)

        def _send_json(self, status: int, payload: dict[str, Any]) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            if not server_token:
                return True
            header = self.headers.get("Authorization", "")
            scheme, _, supplied = header.partition(" ")
            return scheme.lower() == "bearer" and secrets.compare_digest(supplied, server_token)

        def _read_json(self) -> Any:
            content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if content_type != "application/json":
                raise ValueError("Content-Type must be application/json")
            raw_length = self.headers.get("Content-Length")
            if raw_length is None:
                raise ValueError("Content-Length is required")
            try:
                length = int(raw_length)
            except ValueError as exc:
                raise ValueError("Content-Length is invalid") from exc
            if length < 1 or length > MAX_BODY_BYTES:
                raise ValueError(f"request body must be between 1 and {MAX_BODY_BYTES} bytes")
            raw = self.rfile.read(length)
            try:
                return json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise ValueError("request body contains invalid JSON") from exc

        def do_GET(self) -> None:
            if not self._authorized():
                self._send_json(401, {"error": {"message": "unauthorized", "type": "authentication_error"}})
                return
            path = urlsplit(self.path).path
            if path in ("/health", "/healthz"):
                self._send_json(200, {
                    "status": "ok",
                    "service": "new-star-ai",
                    "models_configured": len(inference.registry.list_models(enabled_only=True)),
                })
                return
            if path == "/v1/models":
                models = [
                    {
                        "id": model.model_id,
                        "object": "model",
                        "created": 0,
                        "owned_by": model.provider,
                    }
                    for model in inference.registry.list_models(enabled_only=True)
                    if "chat" in model.capabilities
                ]
                self._send_json(200, {"object": "list", "data": models})
                return
            self._send_json(404, {"error": {"message": "not found", "type": "not_found_error"}})

        def do_POST(self) -> None:
            if not self._authorized():
                self._send_json(401, {"error": {"message": "unauthorized", "type": "authentication_error"}})
                return
            path = urlsplit(self.path).path
            if path not in ("/v1/chat/completions", "/chat/completions"):
                self._send_json(404, {"error": {"message": "not found", "type": "not_found_error"}})
                return
            try:
                request = validate_chat_request(self._read_json())
            except ValueError as exc:
                self._send_json(400, {"error": {"message": str(exc), "type": "invalid_request_error"}})
                return
            try:
                if request["stream"]:
                    self._stream(request)
                else:
                    self._send_json(200, inference.complete(request))
            except NoEligibleModelError as exc:
                self._send_json(503, {"error": {"message": str(exc), "type": "model_unavailable"}})
            except RoutingExhausted as exc:
                self._send_json(502, {
                    "error": {
                        "message": "all configured model routes failed",
                        "type": "upstream_error",
                        "failed_models": [model_id for model_id, _ in exc.failures],
                    }
                })
            except NewStarAIError as exc:
                self._send_json(502, {"error": {"message": str(exc)[:500], "type": "upstream_error"}})
            except (BrokenPipeError, ConnectionResetError):
                return

        def _stream(self, request: dict[str, Any]) -> None:
            response_id, model_id, chunks = inference.stream(request)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache, no-transform")
            self.send_header("Connection", "keep-alive")
            self.send_header("X-Accel-Buffering", "no")
            self.send_header("X-New-Star-Model", model_id)
            self.end_headers()
            try:
                for content in chunks:
                    event = {
                        "id": response_id,
                        "object": "chat.completion.chunk",
                        "created": int(__import__("time").time()),
                        "model": model_id,
                        "choices": [{"index": 0, "delta": {"content": content}, "finish_reason": None}],
                    }
                    self.wfile.write(("data: " + json.dumps(event, ensure_ascii=False) + "\n\n").encode("utf-8"))
                    self.wfile.flush()
                final = {
                    "id": response_id,
                    "object": "chat.completion.chunk",
                    "model": model_id,
                    "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
                }
                self.wfile.write(("data: " + json.dumps(final) + "\n\n").encode("utf-8"))
                self.wfile.write(b"data: [DONE]\n\n")
                self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
            except Exception as exc:
                error = {"error": {"message": str(exc)[:300], "type": "stream_error"}}
                try:
                    self.wfile.write(("data: " + json.dumps(error) + "\n\n").encode("utf-8"))
                    self.wfile.flush()
                except OSError:
                    return

    return InferenceHandler


def create_server(host: str | None = None, port: int | None = None,
                  settings: EngineSettings | None = None) -> ThreadingHTTPServer:
    runtime = settings or EngineSettings.from_env()
    registry = ModelRegistry.from_json_file(runtime.models_file, runtime)
    inference = ChatInference(registry)
    bind_host = host or os.environ.get("NEW_STAR_AI_HOST", "127.0.0.1")
    bind_port = port if port is not None else int(os.environ.get("NEW_STAR_AI_PORT", "8090"))
    token = os.environ.get("NEW_STAR_AI_SERVER_TOKEN", "").strip() or None
    if bind_host not in ("127.0.0.1", "::1", "localhost") and not token:
        raise ValueError("NEW_STAR_AI_SERVER_TOKEN is required when binding outside loopback")
    server = ThreadingHTTPServer((bind_host, bind_port), make_handler(inference, token))
    server.daemon_threads = True
    return server


def main() -> int:
    server = create_server()
    host, port = server.server_address[:2]
    print(f"New Star AI inference server listening on http://{host}:{port}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
