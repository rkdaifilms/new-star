# New Star CPU Inference (first milestone)

This is New Star-owned inference-service code, not a newly trained foundation model. It runs a separately obtained, licensed GGUF language model locally through `llama.cpp` on CPU and exposes a small OpenAI-compatible API.

## What this milestone does

- Loads one GGUF model from a mounted file.
- Runs CPU-only inference (`n_gpu_layers=0`).
- Provides authenticated `POST /v1/chat/completions` and `GET /v1/models` endpoints.
- Provides unauthenticated `GET /healthz` for deployment health checks.
- Limits request size, output tokens, and simultaneous inference.
- Returns generic server errors without exposing prompts, model paths, or credentials.

It does **not** yet generate images, audio, or video; train model weights; stream tokens; or provide multi-tenant quotas.

## Prepare a model

Choose a GGUF model whose license permits your intended use. Check its model card, redistribution terms, hardware requirements, and safety limitations. Do not download model weights from an untrusted source. The model weights are intentionally not stored in this Git repository.

Create a `models` folder and place the chosen model at `models/model.gguf`.

## Run with Docker Compose

```sh
cd ai-inference
cp .env.example .env
# Edit .env and replace MODEL_TOKEN with a long random secret.
docker compose up -d --build
docker compose logs -f
```

The service binds to `127.0.0.1:8000`, not to all public network interfaces. Keep it behind an HTTPS reverse proxy or private network when connecting the hosted New Star gateway. Do not expose the raw inference port publicly.

## Check readiness

```sh
curl http://127.0.0.1:8000/healthz
```

A healthy response has `"ok": true` and `"model_loaded": true`. A missing model or token leaves the service unready.

## Chat request

```sh
curl http://127.0.0.1:8000/v1/chat/completions \
  -H 'Authorization: Bearer replace-with-the-same-MODEL_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "new-star-local",
    "messages": [{"role": "user", "content": "नमस्ते, अपना परिचय दो।"}],
    "max_tokens": 256
  }'
```

## Connect to New Star AI Gateway

This pull request adds a hosted open-model adapter to the New Star AI Gateway source. The adapter becomes usable only after this source is reviewed, merged, and deployed to the active Supabase Edge Function. After the CPU service is deployed behind a reachable HTTPS endpoint and the matching gateway code is deployed, configure these **server-side Supabase Edge Function secrets**:

- `NEW_STAR_MODEL_BASE_URL`: the HTTPS base URL ending in `/v1`, for example `https://your-private-gateway.example/v1`
- `NEW_STAR_MODEL_NAME`: `new-star-local`
- `NEW_STAR_MODEL_API_KEY`: the exact same secret as `MODEL_TOKEN` in this service

The adapter sends requests to `/chat/completions` and checks `/models`, so this service exposes the matching OpenAI-compatible paths. Keep the endpoint behind TLS and authentication. Never put the token in browser JavaScript, HTML, a mobile app bundle, or source control.

The adapter exists in the separately maintained/deployed AI Gateway. Do not redeploy an older Edge Function source branch over the active gateway without first reconciling the newer AI Gateway changes. After secrets are configured, use an authenticated New Star account to test a real chat request and inspect server logs. A successful `/healthz` alone does not prove the hosted gateway can reach the service.

## Resource and production notes

CPU speed and memory use depend heavily on model size, quantization, context length, and host hardware. Start with a small quantized model and measure latency and RAM on the actual host. A CPU service is not unlimited and may be too slow for image/video generation or many concurrent users.

The Compose defaults use two CPU threads and one concurrent generation to avoid overloading a small host. Tune only after measuring the target server. Production also needs TLS, firewall restrictions, token rotation, monitoring, resource limits, backups for configuration, and a plan for model updates.
