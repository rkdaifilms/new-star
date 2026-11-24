"""Standard-library tests for New Star AI Engine Phase 1 and Phase 2."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from ai_engine.config import EngineSettings
from ai_engine.errors import (
    ConfigurationError,
    ModelExecutionError,
    NoEligibleModelError,
    RoutingExhausted,
)
from ai_engine.registry import ModelRegistry, ModelSpec
from ai_engine.router import ModelRouter, RoutingRequest


def make_model(model_id: str, priority: int = 10, capabilities: tuple[str, ...] = ("chat",),
               enabled: bool = True, tags: tuple[str, ...] = (), weight: int = 1) -> ModelSpec:
    return ModelSpec(
        model_id=model_id,
        provider="test-self-hosted",
        endpoint_url=f"http://127.0.0.1:9000/{model_id}",
        protocol="openai_chat",
        model_name=model_id,
        capabilities=frozenset(capabilities),
        priority=priority,
        weight=weight,
        enabled=enabled,
        tags=frozenset(tags),
    )


class EngineSettingsTests(unittest.TestCase):
    def test_environment_settings_are_parsed(self) -> None:
        settings = EngineSettings.from_env({
            "NEW_STAR_AI_MODELS_FILE": "/tmp/models.json",
            "NEW_STAR_AI_FAILURE_THRESHOLD": "3",
            "NEW_STAR_AI_DEFAULT_TIMEOUT_SECONDS": "12.5",
        })
        self.assertEqual(settings.models_file, Path("/tmp/models.json"))
        self.assertEqual(settings.failure_threshold, 3)
        self.assertEqual(settings.default_timeout_seconds, 12.5)

    def test_invalid_environment_setting_is_rejected(self) -> None:
        with self.assertRaises(ConfigurationError):
            EngineSettings.from_env({"NEW_STAR_AI_FAILURE_THRESHOLD": "two"})

    def test_non_finite_timeout_is_rejected(self) -> None:
        with self.assertRaises(ConfigurationError):
            EngineSettings(models_file=Path("unused.json"), default_timeout_seconds=float("nan"))


class ModelRegistryTests(unittest.TestCase):
    def setUp(self) -> None:
        self.settings = EngineSettings(
            models_file=Path("unused.json"),
            failure_threshold=2,
            circuit_cooldown_seconds=60,
            max_circuit_cooldown_seconds=120,
        )

    def test_duplicate_model_ids_are_rejected(self) -> None:
        with self.assertRaises(ConfigurationError):
            ModelRegistry(self.settings, [make_model("same"), make_model("same")])

    def test_endpoint_credentials_are_rejected(self) -> None:
        with self.assertRaises(ConfigurationError):
            ModelSpec(
                model_id="bad", provider="local", endpoint_url="http://user:pass@localhost:8000",
                protocol="openai_chat", model_name="bad", capabilities=frozenset({"chat"})
            )

    def test_json_enabled_field_must_be_boolean(self) -> None:
        with self.assertRaises(ConfigurationError):
            ModelSpec.from_dict({
                "model_id": "bad", "provider": "local", "endpoint_url": "http://127.0.0.1:9000",
                "protocol": "openai_chat", "model_name": "bad", "capabilities": ["chat"],
                "enabled": "false",
            }, 30.0)

    def test_registry_loads_json_and_applies_default_timeout(self) -> None:
        payload = {"models": [{
            "model_id": "local", "provider": "ollama", "endpoint_url": "http://127.0.0.1:11434",
            "protocol": "ollama_chat", "model_name": "local-model", "capabilities": ["chat"]
        }]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "models.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            registry = ModelRegistry.from_json_file(path, self.settings)
        self.assertEqual(registry.get("local").timeout_seconds, self.settings.default_timeout_seconds)

    def test_unknown_capability_is_rejected(self) -> None:
        with self.assertRaises(ConfigurationError):
            make_model("invalid", capabilities=("telepathy",))


class ModelRouterTests(unittest.TestCase):
    def setUp(self) -> None:
        self.settings = EngineSettings(
            models_file=Path("unused.json"),
            failure_threshold=2,
            circuit_cooldown_seconds=60,
            max_circuit_cooldown_seconds=120,
            max_fallback_attempts=4,
        )
        self.primary = make_model("primary", priority=10, tags=("self-hosted",))
        self.secondary = make_model("secondary", priority=20, tags=("self-hosted",))
        self.registry = ModelRegistry(self.settings, [self.primary, self.secondary])
        self.router = ModelRouter(self.registry)

    def test_primary_precedes_lower_priority_fallback(self) -> None:
        plan = self.router.plan(RoutingRequest(capability="chat"))
        self.assertEqual(plan.model_ids, ("primary", "secondary"))

    def test_preferred_model_is_tried_first(self) -> None:
        plan = self.router.plan(RoutingRequest(capability="chat", preferred_model="secondary"))
        self.assertEqual(plan.model_ids[0], "secondary")

    def test_weighted_round_robin_distributes_primary_selection(self) -> None:
        weighted = ModelRegistry(self.settings, [
            make_model("heavy", priority=10, weight=2),
            make_model("light", priority=10, weight=1),
        ])
        weighted.get("heavy")
        heavy_count = 0
        router = ModelRouter(weighted)
        for _ in range(3):
            if router.plan(RoutingRequest(capability="chat")).model_ids[0] == "heavy":
                heavy_count += 1
        self.assertEqual(heavy_count, 2)

    def test_disabled_or_incompatible_models_are_not_selected(self) -> None:
        disabled = make_model("disabled", priority=0, enabled=False)
        image = make_model("image", priority=0, capabilities=("image",))
        registry = ModelRegistry(self.settings, [disabled, image, self.primary])
        plan = ModelRouter(registry).plan(RoutingRequest(capability="chat"))
        self.assertEqual(plan.model_ids, ("primary",))

    def test_tags_and_exclusions_filter_candidates(self) -> None:
        plan = self.router.plan(RoutingRequest(
            capability="chat", required_tags=frozenset({"self-hosted"}),
            excluded_models=frozenset({"primary"})
        ))
        self.assertEqual(plan.model_ids, ("secondary",))

    def test_request_cannot_exceed_configured_fallback_limit(self) -> None:
        registry = ModelRegistry(self.settings, [
            make_model("a", priority=1), make_model("b", priority=2),
            make_model("c", priority=3), make_model("d", priority=4),
            make_model("e", priority=5),
        ])
        plan = ModelRouter(registry).plan(RoutingRequest(capability="chat", max_candidates=50))
        self.assertEqual(len(plan.candidates), self.settings.max_fallback_attempts)

    def test_failure_falls_back_and_marks_primary_unhealthy(self) -> None:
        def execute(model: ModelSpec) -> str:
            if model.model_id == "primary":
                raise ModelExecutionError("connection refused")
            return "ok"
        result = self.router.execute_with_fallback(RoutingRequest(capability="chat"), execute)
        self.assertEqual((result.value, result.model_id, result.attempts), ("ok", "secondary", 2))
        self.assertFalse(self.registry.health("primary").healthy)

    def test_open_circuit_is_skipped(self) -> None:
        self.registry.mark_failure("primary", ModelExecutionError("first failure"))
        self.registry.mark_failure("primary", ModelExecutionError("second failure"))
        plan = self.router.plan(RoutingRequest(capability="chat"))
        self.assertEqual(plan.model_ids, ("secondary",))

    def test_all_failed_routes_raise_typed_error(self) -> None:
        def execute(model: ModelSpec) -> str:
            raise ModelExecutionError("endpoint unavailable")
        with self.assertRaises(RoutingExhausted) as context:
            self.router.execute_with_fallback(RoutingRequest(capability="chat"), execute)
        self.assertEqual(len(context.exception.failures), 2)

    def test_no_matching_capability_is_reported(self) -> None:
        with self.assertRaises(NoEligibleModelError):
            self.router.plan(RoutingRequest(capability="video"))

    def test_success_resets_failure_counter(self) -> None:
        self.registry.mark_failure("primary", ModelExecutionError("temporary"))
        self.registry.mark_success("primary")
        health = self.registry.health("primary")
        self.assertTrue(health.healthy)
        self.assertEqual(health.consecutive_failures, 0)
        self.assertIsNone(health.last_error)


if __name__ == "__main__":
    unittest.main()
