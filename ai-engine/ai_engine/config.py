"""Environment-backed runtime settings for the New Star AI Engine."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from .errors import ConfigurationError


@dataclass(frozen=True, slots=True)
class EngineSettings:
    models_file: Path
    default_timeout_seconds: float = 45.0
    failure_threshold: int = 2
    circuit_cooldown_seconds: float = 20.0
    max_circuit_cooldown_seconds: float = 300.0
    max_fallback_attempts: int = 4

    def __post_init__(self) -> None:
        if self.default_timeout_seconds <= 0:
            raise ConfigurationError("default_timeout_seconds must be positive")
        if self.failure_threshold < 1:
            raise ConfigurationError("failure_threshold must be at least one")
        if self.circuit_cooldown_seconds <= 0:
            raise ConfigurationError("circuit_cooldown_seconds must be positive")
        if self.max_circuit_cooldown_seconds < self.circuit_cooldown_seconds:
            raise ConfigurationError("max circuit cooldown must be >= base cooldown")
        if self.max_fallback_attempts < 1:
            raise ConfigurationError("max_fallback_attempts must be at least one")

    @classmethod
    def from_env(cls, environ: dict[str, str] | None = None) -> "EngineSettings":
        source = os.environ if environ is None else environ

        def number(name: str, default: str) -> float:
            try:
                return float(source.get(name, default))
            except (TypeError, ValueError) as exc:
                raise ConfigurationError(f"{name} must be numeric") from exc

        def integer(name: str, default: str) -> int:
            try:
                return int(source.get(name, default))
            except (TypeError, ValueError) as exc:
                raise ConfigurationError(f"{name} must be an integer") from exc

        models_file = Path(source.get("NEW_STAR_AI_MODELS_FILE", "config/models.json"))
        return cls(
            models_file=models_file,
            default_timeout_seconds=number("NEW_STAR_AI_DEFAULT_TIMEOUT_SECONDS", "45"),
            failure_threshold=integer("NEW_STAR_AI_FAILURE_THRESHOLD", "2"),
            circuit_cooldown_seconds=number("NEW_STAR_AI_CIRCUIT_COOLDOWN_SECONDS", "20"),
            max_circuit_cooldown_seconds=number("NEW_STAR_AI_MAX_CIRCUIT_COOLDOWN_SECONDS", "300"),
            max_fallback_attempts=integer("NEW_STAR_AI_MAX_FALLBACK_ATTEMPTS", "4"),
        )
