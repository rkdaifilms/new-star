"""HTTP adapters for OpenAI-compatible, Ollama, and custom self-hosted models."""

from __future__ import annotations

import json
import os
import socket
import urllib.error
import urllib.request
from collections.abc import Iterator
from typing import Any

from .errors import ModelExecutionError
from .registry import ModelSpec


class UpstreamResponse:
    def __init__(self, response: Any, protocol: str) -> None:
        self.response = response
        self.protocol = protocol

    def close(self) -> None:
        self.response.close()

    def text_chunks(self) -> Iterator[str]:
        try:
            if self.protocol == "openai_chat":
                yield from self._openai_sse()
            else:
                yield from self._json_lines()
        finally:
            self.close()

    def _openai_sse(self) -> Iterator[str]:
        for raw_line in self.response:
            line = raw_line.decode("utf-8", errors="replace").strip()
            if not line or line.startswith(":"):
                continue
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if payload == "[DONE]":
                return
            try:
                item = json.loads(payload)
            except json.JSONDecodeError as exc:
                raise ModelExecutionError("upstream sent malformed SSE JSON") from exc
            if not isinstance(item, dict):
                raise ModelExecutionError("upstream SSE event must contain a JSON object")
            if item.get("error"):
                raise ModelExecutionError(str(item["error"])[:500])
            choices = item.get("choices") or []
            if choices:
                delta = choices[0].get("delta") or {}
                content = delta.get("content")
                if isinstance(content, str) and content:
                    yield content
                elif isinstance(content, list):
                    for part in content:
                        if isinstance(part, dict) and isinstance(part.get("text"), str):
                            yield part["text"]

    def _json_lines(self) -> Iterator[str]:
        for raw_line in self.response:
            line = raw_line.decode("utf-8", errors="replace").strip()
            if not line:
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ModelExecutionError("upstream sent malformed JSON stream line") from exc
            if not isinstance(item, dict):
                raise ModelExecutionError("upstream stream line must contain a JSON object")
            if item.get("error"):
                raise ModelExecutionError(str(item["error"])[:500])
            if self.protocol == "ollama_chat":
                message = item.get("message") or {}
                content = message.get("content")
                if not isinstance(content, str):
                    content = item.get("response")
                if isinstance(content, str) and content:
                    yield content
                if item.get("done") is True:
                    return
            else:
                content = _extract_text(item)
                if content:
                    yield content
                if item.get("done") is True:
                    return


def _extract_text(payload: dict[str, Any]) -> str:
    choices = payload.get("choices")
    if isinstance(choices, list) and choices:
        first = choices[0] if isinstance(choices[0], dict) else {}
        message = first.get("message") or {}
        delta = first.get("delta") or {}
        for value in (delta.get("content"), message.get("content"), first.get("text")):
            if isinstance(value, str):
                return value
    for key in ("output", "text", "response", "content"):
        value = payload.get(key)
        if isinstance(value, str):
            return value
    message = payload.get("message")
    if isinstance(message, dict) and isinstance(message.get("content"), str):
        return message["content"]
    return ""


class ModelHTTPAdapter:
    """Synchronous HTTP adapter; each configured model has a bounded socket timeout."""

    def __init__(self, environ: Any = None) -> None:
        self.environ = os.environ if environ is None else environ

    @staticmethod
    def _url(model: ModelSpec) -> str:
        paths = {
            "openai_chat": "/v1/chat/completions",
            "ollama_chat": "/api/chat",
            "custom_json": "/generate",
        }
        return model.endpoint_url.rstrip("/") + paths[model.protocol]

    def _headers(self, model: ModelSpec) -> dict[str, str]:
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if model.auth_env:
            token = self.environ.get(model.auth_env)
            if not token:
                raise ModelExecutionError(f"required credential environment variable {model.auth_env} is not set")
            headers["Authorization"] = f"Bearer {token}"
        return headers

    def _payload(self, model: ModelSpec, request: dict[str, Any], stream: bool) -> dict[str, Any]:
        messages = request["messages"]
        if model.protocol == "openai_chat":
            payload = {
                "model": model.model_name,
                "messages": messages,
                "stream": stream,
            }
            for key in ("temperature", "top_p", "max_tokens", "stop", "presence_penalty",
                        "frequency_penalty", "seed"):
                if key in request:
                    payload[key] = request[key]
            if request.get("response_format") is not None:
                payload["response_format"] = request["response_format"]
            return payload
        if model.protocol == "ollama_chat":
            options: dict[str, Any] = {}
            for source, target in (("temperature", "temperature"), ("top_p", "top_p"),
                                   ("max_tokens", "num_predict"), ("seed", "seed"),
                                   ("stop", "stop")):
                if source in request:
                    options[target] = request[source]
            payload = {
                "model": model.model_name,
                "messages": messages,
                "stream": stream,
            }
            if options:
                payload["options"] = options
            if request.get("format") is not None:
                payload["format"] = request["format"]
            return payload
        return {
            "model": model.model_name,
            "messages": messages,
            "prompt": "\n".join(f"{item['role']}: {item['content']}" for item in messages),
            "stream": stream,
            "parameters": {
                key: request[key] for key in ("temperature", "top_p", "max_tokens", "stop")
                if key in request
            },
        }

    def open(self, model: ModelSpec, request: dict[str, Any], stream: bool = False) -> Any:
        body = json.dumps(self._payload(model, request, stream), ensure_ascii=False).encode("utf-8")
        outbound = urllib.request.Request(
            self._url(model),
            data=body,
            headers=self._headers(model),
            method="POST",
        )
        timeout = model.timeout_seconds or 45.0
        try:
            return urllib.request.urlopen(outbound, timeout=timeout)
        except urllib.error.HTTPError as exc:
            try:
                detail = exc.read(2048).decode("utf-8", errors="replace")
            finally:
                exc.close()
            raise ModelExecutionError(f"upstream HTTP {exc.code}: {detail[:500]}") from exc
        except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as exc:
            reason = getattr(exc, "reason", exc)
            raise ModelExecutionError(f"upstream connection failed: {reason}") from exc

    def complete(self, model: ModelSpec, request: dict[str, Any]) -> dict[str, Any]:
        response = self.open(model, request, stream=False)
        try:
            try:
                payload = json.loads(response.read().decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                raise ModelExecutionError("upstream returned invalid JSON") from exc
        finally:
            response.close()
        if not isinstance(payload, dict):
            raise ModelExecutionError("upstream response must be a JSON object")
        if payload.get("error"):
            raise ModelExecutionError(str(payload["error"])[:500])
        text = _extract_text(payload)
        if not text and not (payload.get("choices") and payload["choices"][0].get("finish_reason")):
            raise ModelExecutionError("upstream response did not contain assistant text")
        return {"text": text, "raw": payload}

    def open_stream(self, model: ModelSpec, request: dict[str, Any]) -> UpstreamResponse:
        response = self.open(model, request, stream=True)
        return UpstreamResponse(response, model.protocol)
