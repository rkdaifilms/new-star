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
from .inference import ChatInference
from .providers import ModelHTTPAdapter
from .server import create_server, make_handler, validate_chat_request

__all__ = [
    "ChatInference",
    "ConfigurationError",
    "EngineSettings",
    "ExecutionResult",
    "ModelExecutionError",
    "ModelHTTPAdapter",
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
    "create_server",
    "make_handler",
    "validate_chat_request",
]
