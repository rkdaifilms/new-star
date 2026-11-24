"""Validated model registry with in-memory health and circuit-breaker state."""

from __future__ import annotations

import json
import math
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from .config import EngineSettings
from .errors import ConfigurationError, ModelNotFoundError, RegistryError

SUPPORTED_PROTOCOLS = frozenset({"openai_chat", "ollama_chat", "custom_json"})
SUPPORTED_CAPABILITIES = frozenset({
    "chat", "embeddings", "image", "video", "speech_to_text", "text_to_speech", "rerank"
})


@dataclass(frozen=True, slots=True)
class ModelSpec:
    model_id: str
    provider: str
    endpoint_url: str
    protocol: str
    model_name: str
    capabilities: frozenset[str]
    priority: int = 100
    weight: int = 1
    timeout_seconds: float | None = None
    auth_env: str | None = None
    enabled: bool = True
    tags: frozenset[str] = field(default_factory=frozenset)
    health_path: str = "/health"

    def __post_init__(self) -> None:
        for name, value in (("model_id", self.model_id), ("provider", self.provider),
                            ("model_name", self.model_name)):
            if not isinstance(value, str) or not value.strip():
                raise ConfigurationError(f"{name} must be a non-empty string")
        parsed = urlsplit(self.endpoint_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ConfigurationError(f"model {self.model_id!r} endpoint_url must be an absolute HTTP(S) URL")
        if parsed.username is not None or parsed.password is not None:
            raise ConfigurationError("credentials must not be embedded in endpoint_url")
        if parsed.fragment:
            raise ConfigurationError("endpoint_url must not contain a URL fragment")
        if self.protocol not in SUPPORTED_PROTOCOLS:
            raise ConfigurationError(f"unsupported protocol {self.protocol!r}")
        if not self.capabilities or not self.capabilities.issubset(SUPPORTED_CAPABILITIES):
            invalid = sorted(self.capabilities - SUPPORTED_CAPABILITIES)
            raise ConfigurationError(f"invalid or empty model capabilities: {invalid}")
        if self.priority < 0:
            raise ConfigurationError("priority must be zero or greater")
        if self.weight < 1:
            raise ConfigurationError("weight must be at least one")
        if self.timeout_seconds is not None and (
            not math.isfinite(self.timeout_seconds) or self.timeout_seconds <= 0
        ):
            raise ConfigurationError("timeout_seconds must be a finite positive number")
        if self.auth_env is not None and not self.auth_env.replace("_", "").isalnum():
            raise ConfigurationError("auth_env must be an environment variable name")
        if not self.health_path.startswith("/") or ".." in self.health_path.split("/"):
            raise ConfigurationError("health_path must be an absolute path without traversal segments")

    @classmethod
    def from_dict(cls, data: dict[str, Any], default_timeout: float) -> "ModelSpec":
        if not isinstance(data, dict):
            raise ConfigurationError("each model entry must be a JSON object")
        try:
            caps = data["capabilities"]
            tags = data.get("tags", [])
            if not isinstance(caps, list) or not all(isinstance(x, str) for x in caps):
                raise ConfigurationError("capabilities must be an array of strings")
            if not isinstance(tags, list) or not all(isinstance(x, str) for x in tags):
                raise ConfigurationError("tags must be an array of strings")
            timeout = data.get("timeout_seconds", default_timeout)
            return cls(
                model_id=data["model_id"],
                provider=data["provider"],
                endpoint_url=data["endpoint_url"].rstrip("/"),
                protocol=data["protocol"],
                model_name=data["model_name"],
                capabilities=frozenset(caps),
                priority=int(data.get("priority", 100)),
                weight=int(data.get("weight", 1)),
                timeout_seconds=float(timeout),
                auth_env=data.get("auth_env"),
                enabled=bool(data.get("enabled", True)),
                tags=frozenset(tags),
                health_path=data.get("health_path", "/health"),
            )
        except KeyError as exc:
            raise ConfigurationError(f"model entry is missing required field {exc.args[0]!r}") from exc
        except (TypeError, ValueError) as exc:
            if isinstance(exc, ConfigurationError):
                raise
            raise ConfigurationError(f"invalid model entry: {exc}") from exc


@dataclass(slots=True)
class ModelHealth:
    healthy: bool = True
    consecutive_failures: int = 0
    last_error: str | None = None
    last_success_at: float | None = None
    last_failure_at: float | None = None
    circuit_open_until: float = 0.0

    def circuit_is_open(self, now: float | None = None) -> bool:
        return (time.monotonic() if now is None else now) < self.circuit_open_until


class ModelRegistry:
    def __init__(self, settings: EngineSettings, models: list[ModelSpec] | None = None) -> None:
        self.settings = settings
        self._models: dict[str, ModelSpec] = {}
        self._health: dict[str, ModelHealth] = {}
        self._lock = threading.RLock()
        for model in models or []:
            self.register(model)

    @classmethod
    def from_json_file(cls, path: str | Path, settings: EngineSettings) -> "ModelRegistry":
        source = Path(path)
        try:
            payload = json.loads(source.read_text(encoding="utf-8"))
        except OSError as exc:
            raise RegistryError(f"cannot read model registry {source}: {exc}") from exc
        except json.JSONDecodeError as exc:
            raise RegistryError(f"model registry JSON is invalid at line {exc.lineno}: {exc.msg}") from exc
        if not isinstance(payload, dict) or not isinstance(payload.get("models"), list):
            raise RegistryError("model registry must be an object containing a models array")
        models = [ModelSpec.from_dict(item, settings.default_timeout_seconds) for item in payload["models"]]
        return cls(settings, models)

    def register(self, model: ModelSpec) -> None:
        if not isinstance(model, ModelSpec):
            raise TypeError("model must be a ModelSpec")
        with self._lock:
            if model.model_id in self._models:
                raise ConfigurationError(f"duplicate model_id {model.model_id!r}")
            self._models[model.model_id] = model
            self._health[model.model_id] = ModelHealth()

    def get(self, model_id: str) -> ModelSpec:
        with self._lock:
            try:
                return self._models[model_id]
            except KeyError as exc:
                raise ModelNotFoundError(model_id) from exc

    def list_models(self, enabled_only: bool = False) -> tuple[ModelSpec, ...]:
        with self._lock:
            values = tuple(self._models.values())
        if enabled_only:
            return tuple(model for model in values if model.enabled)
        return values

    def health(self, model_id: str) -> ModelHealth:
        with self._lock:
            try:
                current = self._health[model_id]
            except KeyError as exc:
                raise ModelNotFoundError(model_id) from exc
            return ModelHealth(
                healthy=current.healthy,
                consecutive_failures=current.consecutive_failures,
                last_error=current.last_error,
                last_success_at=current.last_success_at,
                last_failure_at=current.last_failure_at,
                circuit_open_until=current.circuit_open_until,
            )

    def is_eligible(self, model: ModelSpec, capability: str, required_tags: frozenset[str],
                    now: float | None = None) -> bool:
        with self._lock:
            health = self._health[model.model_id]
            return (
                model.enabled
                and capability in model.capabilities
                and required_tags.issubset(model.tags)
                and not health.circuit_is_open(now)
            )

    def mark_success(self, model_id: str) -> None:
        with self._lock:
            health = self._health.get(model_id)
            if health is None:
                raise ModelNotFoundError(model_id)
            health.healthy = True
            health.consecutive_failures = 0
            health.last_error = None
            health.last_success_at = time.monotonic()
            health.circuit_open_until = 0.0

    def mark_failure(self, model_id: str, error: BaseException) -> None:
        with self._lock:
            health = self._health.get(model_id)
            if health is None:
                raise ModelNotFoundError(model_id)
            health.consecutive_failures += 1
            health.healthy = False
            health.last_error = f"{type(error).__name__}: {error}"[:500]
            health.last_failure_at = time.monotonic()
            if health.consecutive_failures >= self.settings.failure_threshold:
                exponent = health.consecutive_failures - self.settings.failure_threshold
                cooldown = min(
                    self.settings.circuit_cooldown_seconds * (2 ** exponent),
                    self.settings.max_circuit_cooldown_seconds,
                )
                health.circuit_open_until = health.last_failure_at + cooldown

    def reset_health(self, model_id: str) -> None:
        with self._lock:
            if model_id not in self._health:
                raise ModelNotFoundError(model_id)
            self._health[model_id] = ModelHealth()
