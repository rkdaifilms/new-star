"""Capability-aware routing and bounded fallback execution."""

from __future__ import annotations

import threading
from dataclasses import dataclass, field
from typing import Callable, Generic, TypeVar

from .errors import ModelExecutionError, NoEligibleModelError, RoutingExhausted
from .registry import ModelRegistry, ModelSpec

T = TypeVar("T")


@dataclass(frozen=True, slots=True)
class RoutingRequest:
    capability: str
    preferred_model: str | None = None
    required_tags: frozenset[str] = field(default_factory=frozenset)
    excluded_models: frozenset[str] = field(default_factory=frozenset)
    request_id: str | None = None
    language: str | None = None
    max_candidates: int | None = None

    def __post_init__(self) -> None:
        if not self.capability:
            raise ValueError("capability is required")
        if self.language is not None and not self.language.strip():
            raise ValueError("language must be non-empty when provided")
        if self.max_candidates is not None and self.max_candidates < 1:
            raise ValueError("max_candidates must be positive when provided")


@dataclass(frozen=True, slots=True)
class RoutePlan:
    request: RoutingRequest
    candidates: tuple[ModelSpec, ...]

    @property
    def model_ids(self) -> tuple[str, ...]:
        return tuple(model.model_id for model in self.candidates)


@dataclass(frozen=True, slots=True)
class ExecutionResult(Generic[T]):
    value: T
    model_id: str
    attempts: int


class ModelRouter:
    def __init__(self, registry: ModelRegistry) -> None:
        self.registry = registry
        self._rotation: dict[tuple[int, tuple[str, ...]], int] = {}
        self._weighted_current: dict[tuple[int, tuple[str, ...]], dict[str, int]] = {}
        self._lock = threading.Lock()

    def _ordered_by_priority(self, models: list[ModelSpec]) -> list[ModelSpec]:
        groups: dict[int, list[ModelSpec]] = {}
        for model in models:
            groups.setdefault(model.priority, []).append(model)
        ordered: list[ModelSpec] = []
        for priority in sorted(groups):
            group = sorted(groups[priority], key=lambda item: item.model_id)
            if len(group) > 1:
                key = (priority, tuple(item.model_id for item in group))
                with self._lock:
                    current = self._weighted_current.setdefault(key, {item.model_id: 0 for item in group})
                    total_weight = sum(item.weight for item in group)
                    for item in group:
                        current[item.model_id] += item.weight
                    selected = max(group, key=lambda item: (current[item.model_id], item.model_id))
                    current[selected.model_id] -= total_weight
                    remainder = [item for item in group if item.model_id != selected.model_id]
                    offset = self._rotation.get(key, 0) % len(remainder) if remainder else 0
                    self._rotation[key] = offset + 1
                if remainder:
                    remainder = remainder[offset:] + remainder[:offset]
                group = [selected, *remainder]
            ordered.extend(group)
        return ordered

    def plan(self, request: RoutingRequest) -> RoutePlan:
        if request.preferred_model:
            preferred = self.registry.get(request.preferred_model)
        else:
            preferred = None
        eligible = [
            model for model in self.registry.list_models()
            if model.model_id not in request.excluded_models
            and self.registry.is_eligible(
                model, request.capability, request.required_tags, request.language
            )
        ]
        ordered = self._ordered_by_priority(eligible)
        if preferred is not None:
            ordered = ([preferred] + [model for model in ordered if model.model_id != preferred.model_id]
                       if preferred in eligible else ordered)
        configured_limit = self.registry.settings.max_fallback_attempts
        requested_limit = request.max_candidates or configured_limit
        limit = min(requested_limit, configured_limit)
        candidates = tuple(ordered[:limit])
        if not candidates:
            raise NoEligibleModelError(
                f"no enabled healthy model supports capability {request.capability!r}"
            )
        return RoutePlan(request=request, candidates=candidates)

    def execute_with_fallback(self, request: RoutingRequest,
                              executor: Callable[[ModelSpec], T]) -> ExecutionResult[T]:
        plan = self.plan(request)
        failures: list[tuple[str, str]] = []
        for attempt, model in enumerate(plan.candidates, start=1):
            try:
                result = executor(model)
            except ModelExecutionError as exc:
                self.registry.mark_failure(model.model_id, exc)
                failures.append((model.model_id, f"{type(exc).__name__}: {exc}"[:500]))
                continue
            self.registry.mark_success(model.model_id)
            return ExecutionResult(value=result, model_id=model.model_id, attempts=attempt)
        raise RoutingExhausted(failures)
