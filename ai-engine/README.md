# New Star AI Engine — Phases 1–4

This Python 3.11+ package provides a provider-neutral model registry, capability/language-aware router, bounded circuit-breaker fallback, and a dependency-free HTTP inference server. The engine calls only endpoints explicitly configured by the operator. It does not require a paid hosted API; actual model weights and model-serving processes are deployed separately.

## Layout

- `ai_engine/config.py`: environment-backed runtime settings.
- `ai_engine/errors.py`: typed configuration, routing, and execution errors.
- `ai_engine/registry.py`: validated model definitions, JSON loading, health and circuit-breaker state.
- `ai_engine/router.py`: capability-aware candidate selection and bounded fallback execution.
- `ai_engine/providers.py`: OpenAI-compatible, Ollama, and custom JSON HTTP adapters.
- `ai_engine/inference.py`: chat orchestration, response normalization, and streaming selection.
- `ai_engine/server.py`: threaded HTTP server, request validation, OpenAI-compatible API, and SSE output.
- `ai_engine/security.py`: API-key registry, token-bucket rate limiter, and rotating privacy-safe usage log.
- `config/models.example.json`: example self-hosted model registry.
- `tests/`: standard-library unit and HTTP integration tests.

## Configure

From the repository root:

```sh
cp ai-engine/config/models.example.json ai-engine/config/models.json
```

Edit `ai-engine/config/models.json` so each endpoint and model name matches a model server that you operate. For Ollama, set the endpoint to its base URL (for example `http://127.0.0.1:11434`) and protocol to `ollama_chat`. For vLLM, llama.cpp server, LocalAI, or another OpenAI-compatible server, use protocol `openai_chat` and its base URL. For a custom adapter, the server accepts a JSON `POST /generate` request with `model`, `messages`, `prompt`, `stream`, and `parameters` fields and returns an object containing `output`, `text`, `response`, or `content`.

Set:

```sh
export NEW_STAR_AI_MODELS_FILE="$PWD/ai-engine/config/models.json"
export NEW_STAR_AI_HOST=127.0.0.1
export NEW_STAR_AI_PORT=8090
export NEW_STAR_AI_DEFAULT_TIMEOUT_SECONDS=45
export NEW_STAR_AI_FAILURE_THRESHOLD=2
export NEW_STAR_AI_CIRCUIT_COOLDOWN_SECONDS=20
```

If a model uses `auth_env`, set that named environment variable to its bearer token. Do not put tokens in the JSON file or commit production credentials. Binding the inference server to a non-loopback interface requires `NEW_STAR_AI_SERVER_TOKEN`; clients must send `Authorization: Bearer <token>`. Keep the service behind a trusted reverse proxy/firewall for internet access.

## Start the server

From the repository root:

```sh
PYTHONPATH=ai-engine python -m ai_engine --models-file ai-engine/config/models.json serve --host 127.0.0.1 --port 8090
```

Alternatively, run `PYTHONPATH=ai-engine python -m ai_engine.server` after setting the environment variables. The service uses only the Python standard library.

## Route requests to the New Star-owned experimental foundation checkpoint

The separate native checkpoint server can be registered as an OpenAI-compatible chat
upstream for this router. This is a real integration path, but the checkpoint must
first be trained locally and its output quality depends on the dataset and hardware.
It is not equivalent to the Qwen bootstrap model or a frontier assistant.

From the repository root, after creating `artifacts/newstar-small.pt` with the
`ai-foundation/train.py` workflow, run both local services in one shell:

```sh
export NEW_STAR_FOUNDATION_API_KEY="$(python -c 'import secrets; print(secrets.token_urlsafe(32))')"
python ai-foundation/serve.py --checkpoint artifacts/newstar-small.pt --host 127.0.0.1 --port 8765 &
FOUNDATION_PID=$!
trap 'kill "$FOUNDATION_PID" 2>/dev/null || true' EXIT
PYTHONPATH=ai-engine python -m ai_engine --models-file ai-engine/config/models.foundation.example.json serve --host 127.0.0.1 --port 8090
```

The same environment variable authenticates the foundation server and the router's
upstream requests. The registry points only to loopback and does not expose the
checkpoint directly to the internet. The native foundation adapter currently supports
non-streaming chat requests; send `"stream": false` until SSE generation is implemented.
The router's own streaming endpoint cannot make a non-streaming upstream stream.

## API

- `GET /health` and `GET /healthz`: service status and configured model count.
- `GET /v1/models`: enabled chat models in OpenAI model-list format.
- `POST /v1/chat/completions` and `POST /chat/completions`: OpenAI-compatible chat completion.
- Set `"stream": true` to receive Server-Sent Events in OpenAI chat-completion chunk format, ending with `data: [DONE]`.

Example non-streaming request:

```sh
curl http://127.0.0.1:8090/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"auto","messages":[{"role":"user","content":"नमस्ते"}]}'
```

Example streaming request:

```sh
curl -N http://127.0.0.1:8090/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"auto","stream":true,"messages":[{"role":"user","content":"Hello"}]}'
```

Supported request fields include `model`, `messages`, `stream`, `temperature`, `top_p`, `max_tokens`, `stop`, `seed`, `response_format`, `format`, `user`, and `language`. The server limits request bodies to 1 MiB and validates message roles and common sampling fields.

## New Star-owned experimental image service

The separate `ai-image/` package now includes a from-scratch unconditional DDPM training pipeline and a local authenticated PNG-generation API. Train a checkpoint with a permitted image dataset, then run:

```sh
python ai-image/serve.py --checkpoint artifacts/newstar-image-ddpm.pt --host 127.0.0.1 --port 8780
```

The image endpoint is `POST http://127.0.0.1:8780/v1/images/generations`. It returns base64 PNG images and supports a request-local seed. This experimental image model is not wired into the chat router and does not support text prompts; do not advertise it as text-to-image. See `ai-image/README.md` for dataset provenance, training, generation, and API details.

## Adapters and fallback

- `openai_chat`: sends `POST /v1/chat/completions`; supports OpenAI JSON and SSE responses.
- `ollama_chat`: sends `POST /api/chat`; supports Ollama JSON and newline-delimited streaming responses.
- `custom_json`: sends `POST /generate`; accepts JSON output fields `output`, `text`, `response`, or `content`; streaming accepts newline-delimited JSON records.

For non-streaming requests, connection failures, timeouts, non-success HTTP statuses, and invalid upstream payloads trigger the router's bounded fallback. For streaming, fallback is attempted until an upstream has successfully returned response headers; after downstream SSE headers or generated content have been sent, switching models would corrupt the stream, so a mid-stream error is returned as an SSE error event instead. Socket timeouts are set per model.

The API is loopback-only by default. When exposed on another interface, a server bearer token is mandatory. Phase 4 adds optional client API keys, per-client token-bucket limits, a rotating JSONL usage log, and the `/v1/new-star/chat` alias. Set `NEW_STAR_AI_API_KEYS` to comma-separated `client_id:secret` pairs; when configured, these keys are required for every route. Keep secrets in environment variables, never in the model JSON or Git. Configure `NEW_STAR_AI_RATE_LIMIT_CAPACITY` (default 60) and `NEW_STAR_AI_RATE_LIMIT_REFILL_PER_SECOND` (default 1). Set `NEW_STAR_AI_USAGE_LOG` to enable a rotating log (5 MB, three backups). Logs contain request metadata only—never prompts, completions, or bearer tokens. The HTTP API now stores token-bucket state in SQLite (NEW_STAR_AI_RATE_LIMIT_DB; defaults to the job queue database), so rate limits survive restarts and are shared by API processes on the same host. This does not provide cross-host or multi-region coordination. The New Star route accepts the same OpenAI-compatible chat payload.

## Tests

```sh
python -m unittest discover -s ai-engine/tests -v
```

CI compiles the package, runs router and HTTP integration tests, validates the example registry, and previews a Hindi route. Tests use local fake HTTP servers; they do not require paid APIs or download model weights.

## Important operational boundary

The inference server is the HTTP orchestration layer, not a trained foundation model. It does not create model weights or provide GPU compute. To generate responses, configure at least one reachable model server with compatible model weights. The independent New Star foundation model remains an experimental training codebase until it has been trained and evaluated on a suitable corpus and hardware.


## Durable background jobs

Submit non-streaming chat requests with POST /v1/jobs using the same validated JSON payload as the synchronous endpoint. The API returns HTTP 202 and a Location header containing /v1/jobs/{id}; poll that resource for queued, running, succeeded, or failed. Successful jobs include the normal chat completion response in result. Jobs are scoped to the authenticated client identity. An Idempotency-Key header (up to 128 visible characters) makes safe retries return the original job.

Configure NEW_STAR_AI_QUEUE_DB (default var/new-star-jobs.sqlite3), NEW_STAR_AI_QUEUE_MAX_PENDING (default 1000), NEW_STAR_AI_JOB_LEASE_SECONDS (default 300), and NEW_STAR_AI_JOB_MAX_ATTEMPTS (default 3), and NEW_STAR_AI_JOB_RETENTION_DAYS (default 30; finished jobs older than this are pruned during enqueue). SQLite WAL and transactional claims coordinate multiple worker processes on one host with a shared local database volume. Expired worker leases are recovered; failures retry with capped exponential backoff. Do not place this SQLite database on an unreliable network filesystem or treat this single-host queue as a multi-region distributed queue.

For automatic same-host process scaling, the Compose worker runs the queue-depth supervisor (worker_pool). It scales from zero to NEW_STAR_AI_WORKER_MAX_PROCESSES based on queued plus running jobs, using NEW_STAR_AI_WORKER_JOBS_PER_PROCESS as the target ratio. Idle workers exit after NEW_STAR_AI_WORKER_IDLE_TIMEOUT_SECONDS. Configure conservative limits based on measured capacity. Run a single worker directly with PYTHONPATH=ai-engine python -m ai_engine.worker when debugging. This is local process autoscaling, not multi-host cluster autoscaling; API and workers must share the same persistent queue volume on one host.

Queue operations use the API authentication and rate limiter. GET /v1/queue returns aggregate counts; job payloads and results are visible only to the owning client.


## Container deployment for API and workers

From the repository root, copy the example model registry and configure a long random API token in the shell. The registry must point to reachable model-serving endpoints; keep provider credentials in environment variables.

```sh
cp ai-engine/config/models.example.json ai-engine/config/models.json
cd ai-engine
export NEW_STAR_AI_SERVER_TOKEN='replace-with-a-long-random-secret'
docker compose -f docker-compose.yml up -d --build
docker compose -f docker-compose.yml logs -f
```

Increase the same-host worker pool with:

```sh
docker compose -f docker-compose.yml up -d --build --scale new-star-worker=3
```

This is manual, capacity-bounded worker scaling, not automatic metrics-driven autoscaling. The API and all workers share one SQLite database volume and must stay on the same Docker host. For multi-host or automatic autoscaling, replace SQLite with a managed transactional queue and deploy workers under Kubernetes or another orchestrator using queue-depth, latency, and CPU/GPU memory metrics. Do not share this SQLite volume across hosts.
