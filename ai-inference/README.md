# New Star CPU Inference (first milestone)

This is New Star-owned inference-service code, not a newly trained foundation model. It runs a separately obtained, licensed GGUF language model locally through `llama.cpp` on CPU and exposes a small OpenAI-compatible API.

## What this milestone does

- Loads one GGUF model from a mounted file.
- Runs CPU-only inference (`n_gpu_layers=0`).
- Provides authenticated `POST /v1/chat/completions` and `GET /v1/models` endpoints.
- Provides unauthenticated `GET /healthz` for deployment health checks.
- Limits request size, output tokens, and simultaneous inference.
- Returns generic server errors without exposing prompts, model paths, or credentials.

It does **not** yet generate images, audio, or video; train model weights; stream tokens; provide multi-tenant quotas; or connect itself to the Supabase Edge Function. Those are later milestones.

## Build and run

Choose a GGUF model whose license permits your intended use. Check its model card, redistribution terms, hardware requirements, and safety limitations. Do not download model weights from an untrusted source.

```sh
docker build -t new-star-cpu-inference ./ai-inference

docker run --rm -p 8000:8000 \
  -e MODEL_TOKEN='replace-with-a-long-random-secret' \
  -e MODEL_ID='new-star-local' \
  -e N_THREADS=4 \
  -v "$PWD/models:/models:ro" \
  new-star-cpu-inference
```

Place the chosen model at `./models/model.gguf` before starting the container. The model file is intentionally not stored in this Git repository. Keep `MODEL_TOKEN` private and use HTTPS plus a private network or trusted reverse proxy in production. Never put this token in browser JavaScript, HTML, mobile app bundles, or source control.

## Check readiness

```sh
curl http://localhost:8000/healthz
```

A healthy response has `"ok": true` and `"model_loaded": true`. A missing model or token leaves the service unready.

## Chat request

```sh
curl http://localhost:8000/v1/chat/completions \
  -H 'Authorization: Bearer replace-with-a-long-random-secret' \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "new-star-local",
    "messages": [{"role": "user", "content": "नमस्ते, अपना परिचय दो।"}],
    "max_tokens": 256
  }'
```

## Resource and production notes

CPU speed and memory use depend heavily on model size, quantization, context length, and host hardware. Start with a small quantized model and measure latency and RAM on the actual host. A CPU service is not unlimited and may be too slow for image/video generation or many concurrent users.

For production, deploy on a host that can keep the container and model file running, terminate TLS, restrict inbound access, rotate the bearer token if exposed, and monitor memory, CPU, latency, and failures. Do not expose this service directly to browsers. The current Supabase AI Gateway does not call this service yet; that integration must be added only after a reachable HTTPS endpoint and server-side token are configured.
