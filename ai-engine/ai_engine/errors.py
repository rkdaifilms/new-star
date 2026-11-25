"""Typed errors for New Star AI Engine configuration and routing."""

from __future__ import annotations


class NewStarAIError(Exception):
    """Base exception for engine failures."""


class ConfigurationError(NewStarAIError, ValueError):
    """Runtime or model configuration is invalid."""


class RegistryError(NewStarAIError):
    """A model registry operation failed."""


class ModelNotFoundError(RegistryError, KeyError):
    """A requested model ID is not registered."""


class ModelExecutionError(NewStarAIError):
    """An endpoint attempt failed in a way that permits fallback."""


class NoEligibleModelError(NewStarAIError):
    """No enabled, healthy model satisfies the routing request."""


class RoutingExhausted(NewStarAIError):
    """All eligible model attempts failed."""

    def __init__(self, failures: list[tuple[str, str]]) -> None:
        self.failures = tuple(failures)
        detail = "; ".join(f"{model_id}: {message}" for model_id, message in failures)
        super().__init__(f"all model routes failed ({detail})")
