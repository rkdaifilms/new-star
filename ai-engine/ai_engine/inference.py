"""Chat orchestration, bounded fallback, and OpenAI-compatible response shaping."""

from __future__ import annotations

import time
import uuid
from collections.abc import Iterator
from typing import Any

from .errors import ModelExecutionError, NoEligibleModelError, RoutingExhausted
from .providers import ModelHTTPAdapter
from .registry import ModelRegistry
from .router import ModelRouter, RoutingRequest


class ChatInference:
    def __init__(self, registry: ModelRegistry, adapter: ModelHTTPAdapter | None = None) -> None:
        self.registry = registry
        self.router = ModelRouter(registry)
        self.adapter = adapter or ModelHTTPAdapter()

    def complete(self, request: dict[str, Any]) -> dict[str, Any]:
        route_request = RoutingRequest(
            capability="chat",
            preferred_model=request.get("model") if request.get("model") not in (None, "", "auto") else None,
            language=request.get("language"),
            request_id=request.get("user"),
        )
        result = self.router.execute_with_fallback(
            route_request,
            lambda model: self.adapter.complete(model, request),
        )
        raw = result.value["raw"]
        model_id = result.model_id
        message = {"role": "assistant", "content": result.value["text"]}
        choice = {
            "index": 0,
            "message": message,
            "finish_reason": _finish_reason(raw),
        }
        response = {
            "id": _response_id(raw),
            "object": "chat.completion",
            "created": _created(raw),
            "model": model_id,
            "choices": [choice],
            "usage": _usage(raw, request, result.value["text"]),
            "system_fingerprint": raw.get("system_fingerprint"),
        }
        if response["system_fingerprint"] is None:
            response.pop("system_fingerprint")
        return response

    def stream(self, request: dict[str, Any]) -> tuple[str, str, Iterator[str]]:
        route_request = RoutingRequest(
            capability="chat",
            preferred_model=request.get("model") if request.get("model") not in (None, "", "auto") else None,
            language=request.get("language"),
            request_id=request.get("user"),
        )
        plan = self.router.plan(route_request)
        failures: list[tuple[str, str]] = []
        opened = None
        selected = None
        for model in plan.candidates:
            try:
                opened = self.adapter.open_stream(model, request)
                selected = model
                break
            except ModelExecutionError as exc:
                self.registry.mark_failure(model.model_id, exc)
                failures.append((model.model_id, f"{type(exc).__name__}: {exc}"[:500]))
        if opened is None or selected is None:
            raise RoutingExhausted(failures)
        response_id = f"chatcmpl-{uuid.uuid4().hex}"
        created = int(time.time())

        def chunks() -> Iterator[str]:
            try:
                yield from opened.text_chunks()
            except ModelExecutionError as exc:
                self.registry.mark_failure(selected.model_id, exc)
                raise
            except (OSError, TimeoutError) as exc:
                error = ModelExecutionError(f"upstream stream interrupted: {exc}")
                self.registry.mark_failure(selected.model_id, error)
                raise error from exc
            else:
                self.registry.mark_success(selected.model_id)

        return response_id, selected.model_id, chunks()


def _response_id(raw: dict[str, Any]) -> str:
    value = raw.get("id")
    return value if isinstance(value, str) and value else f"chatcmpl-{uuid.uuid4().hex}"


def _created(raw: dict[str, Any]) -> int:
    value = raw.get("created")
    return value if isinstance(value, int) else int(time.time())


def _finish_reason(raw: dict[str, Any]) -> str:
    choices = raw.get("choices")
    if isinstance(choices, list) and choices and isinstance(choices[0], dict):
        value = choices[0].get("finish_reason")
        if isinstance(value, str):
            return value
    return "stop"


def _token_estimate(text: str) -> int:
    return max(1, (len(text.encode("utf-8")) + 3) // 4) if text else 0


def _usage(raw: dict[str, Any], request: dict[str, Any], answer: str) -> dict[str, int]:
    usage = raw.get("usage")
    if isinstance(usage, dict):
        prompt = usage.get("prompt_tokens", usage.get("input_tokens"))
        completion = usage.get("completion_tokens", usage.get("output_tokens"))
        total = usage.get("total_tokens")
        if all(isinstance(value, int) and value >= 0 for value in (prompt, completion)):
            return {
                "prompt_tokens": prompt,
                "completion_tokens": completion,
                "total_tokens": total if isinstance(total, int) and total >= 0 else prompt + completion,
            }
    prompt_text = " ".join(str(item.get("content", "")) for item in request["messages"])
    prompt_tokens = _token_estimate(prompt_text)
    completion_tokens = _token_estimate(answer)
    return {
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "total_tokens": prompt_tokens + completion_tokens,
    }
