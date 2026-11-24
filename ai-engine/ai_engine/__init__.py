"""Provider-neutral New Star model registry and routing core."""

from .config import EngineSettings
from .errors import (
    ConfigurationError,
    ModelExecutionError,
    ModelNotFoundError,
    NoEligibleModelError,
    RegistryError,
    RoutingExhausted,
)
from .registry import ModelHealth, ModelRegistry, ModelSpec
from .router import ExecutionResult, ModelRouter, RoutePlan, RoutingRequest

__all__ = [
    "ConfigurationError",
    "EngineSettings",
    "ExecutionResult",
    "ModelExecutionError",
    "ModelHealth",
    "ModelNotFoundError",
    "ModelRegistry",
    "ModelRouter",
    "ModelSpec",
    "NoEligibleModelError",
    "RegistryError",
    "RoutePlan",
    "RoutingExhausted",
    "RoutingRequest",
]
