# New Star AI Engine — Phase 1 and Phase 2

This package establishes a provider-neutral, self-hostable model registry and deterministic fallback router. It does not require a paid hosted API. Model processes remain separately deployed; this router selects configured endpoints and records health state. Phase 3 will add network inference and streaming; Phase 4 will add application authentication, rate limits, usage logs, and the New Star integration endpoint.

## Layout

- `ai_engine/config.py`: environment-backed runtime settings.
- `ai_engine/errors.py`: typed registry and routing failures.
- `ai_engine/registry.py`: validated model definitions, JSON loading, health and circuit-breaker state.
- `ai_engine/router.py`: capability-aware candidate selection and bounded fallback execution.
- `config/models.example.json`: self-hosted endpoint configuration example.
- `tests/test_router.py`: standard-library unit tests.

## Requirements

Python 3.11 or newer. Phase 1 and Phase 2 use only the Python standard library.

## Configure

Copy `config/models.example.json` to a private runtime path and set:

```sh
export NEW_STAR_AI_MODELS_FILE=/etc/new-star-ai/models.json
export NEW_STAR_AI_DEFAULT_TIMEOUT_SECONDS=45
export NEW_STAR_AI_FAILURE_THRESHOLD=2
export NEW_STAR_AI_CIRCUIT_COOLDOWN_SECONDS=20
```

Set each model's `auth_env` to the name of an environment variable containing its bearer token. The registry stores only the variable name and never reads or logs the secret value. Do not commit private model URLs, credentials, or production config.

## Run tests

From the repository root:

```sh
python -m unittest discover -s ai-engine/tests -v
```

## Validate and preview routes

```sh
PYTHONPATH=ai-engine python -m ai_engine --models-file ai-engine/config/models.example.json validate
PYTHONPATH=ai-engine python -m ai_engine --models-file ai-engine/config/models.example.json plan --capability chat --language hi
```

The CLI validates configuration and previews route selection locally. It never calls a model endpoint.

## Minimal usage

```python
from ai_engine.config import EngineSettings
from ai_engine.registry import ModelRegistry
from ai_engine.router import ModelRouter, RoutingRequest

settings = EngineSettings.from_env()
registry = ModelRegistry.from_json_file(settings.models_file, settings)
router = ModelRouter(registry)
plan = router.plan(RoutingRequest(capability="chat", preferred_model="newstar-chat"))
for model in plan.candidates:
    print(model.model_id, model.endpoint_url)
```

The plan contains endpoint metadata only. It does not make network calls. A caller can pass a transport function to `execute_with_fallback`; Phase 3 will provide the production HTTP and streaming transport.

## Routing behavior

1. Disabled, capability-incompatible, tag-incompatible, excluded, or open-circuit models are omitted.
2. A preferred model is tried first when it is eligible.
3. Models are filtered by requested language when a model declares language support; an empty language list or `*` means all languages are supported.
4. Remaining models are ordered by ascending priority, then weighted round-robin within equal-priority groups.
5. A failed attempt increments that model's failure count and opens its circuit after the configured threshold.
6. A successful attempt resets its failure count and circuit state.
7. If every candidate fails, a typed `RoutingExhausted` error is raised with model IDs and safe error messages.

Only endpoints explicitly configured by the operator are contacted. Private-network endpoints are supported for self-hosted models. The router does not discover or call arbitrary user-supplied URLs.
