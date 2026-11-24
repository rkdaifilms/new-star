# New Star CPU Inference (first milestone)

This is New Star-owned inference-service code, not a newly trained foundation model or the production ChatGPT/Gemini model. It runs a separately obtained, licensed GGUF language model locally through `llama.cpp` on CPU as an opt-in test engine and exposes a small OpenAI-compatible API.

## What this milestone does

- Loads one GGUF model from a mounted file.
- Runs CPU inference by default; `N_GPU_LAYERS` and `N_BATCH` configure optional GPU offload when the installed `llama-cpp-python` build has CUDA support.
- Provides authenticated `POST /v1/chat/completions` and `GET /v1/models` endpoints.
- Streams chat tokens over Server-Sent Events when `stream: true` is requested, so the UI can show the first tokens before the full answer is ready.
- Provides unauthenticated `GET /healthz` for deployment health checks.
- Limits request size, output tokens, and simultaneous inference.
- Returns generic server errors without exposing prompts, model paths, or credentials.

It does **not** yet generate images, audio, or video, train its own foundation-model weights, or provide multi-tenant quotas. Text streaming is supported.

## Default model bootstrap

The default Compose setup downloads and verifies a small Qwen3 0.6B Q4_0 GGUF model from the ggml-org Hugging Face repository at container startup. The pinned file is about 429 MB and its published model page identifies the license as Apache-2.0. The downloader checks the pinned SHA-256 before the service starts; an incomplete or changed file is rejected. Model weights are intentionally not stored in Git.

- Model: [ggml-org/Qwen3-0.6B-GGUF](https://huggingface.co/ggml-org/Qwen3-0.6B-GGUF)
- File: `Qwen3-0.6B-Q4_0.gguf`
- SHA-256: `da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4`
- Approximate download: 429 MB

This 0.6B model is a bootstrap/local CPU model, not a frontier ChatGPT/Gemini replacement. Expect lower reasoning quality and slower CPU responses. A stronger model needs more RAM/compute and a separately reviewed model choice. The downloader supports intentional overrides using `MODEL_URL` and `MODEL_SHA256`; do not change only one of them.

## Run with Docker Compose

```sh
cd ai-inference
cp .env.example .env
# Edit .env and replace MODEL_TOKEN with a long random secret.
docker compose up -d --build
docker compose logs -f
```

On first start, Docker downloads the model into `ai-inference/models`, verifies its checksum, and then launches the API. The first startup therefore needs internet access and roughly 500 MB of free disk space for the model file plus temporary download. Later starts reuse the verified file. Keep `models` writable by the container so the bootstrap can save the download.

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

## Stream a response

Use `curl -N` to keep the connection open and display each generated chunk as it arrives:

```sh
curl -N http://127.0.0.1:8000/v1/chat/completions \\
  -H 'Authorization: Bearer replace-with-the-same-MODEL_TOKEN' \\
  -H 'Content-Type: application/json' \\
  -d '{"model":"new-star-local","messages":[{"role":"user","content":"नमस्ते"}],"stream":true,"max_tokens":256}'
```

The response uses OpenAI-compatible `data: ...` events and ends with `data: [DONE]`. Streaming improves perceived responsiveness; it does not make the model compute tokens faster.

## Connect to New Star AI Gateway

This pull request adds an opt-in local test-model adapter to the New Star AI Gateway source. It is not the production ChatGPT/Gemini model and is not enabled by default. After the CPU service is deployed behind a reachable HTTPS endpoint and the matching gateway code is deployed, an authorized project owner may configure these **server-side Supabase Edge Function secrets**:

- `NEW_STAR_MODEL_BASE_URL`: the HTTPS base URL ending in `/v1`, for example `https://your-private-gateway.example/v1`
- `NEW_STAR_MODEL_NAME`: `new-star-qwen3-0.6b`
- `NEW_STAR_MODEL_API_KEY`: the exact same secret as `MODEL_TOKEN` in this service
- `NEW_STAR_MODEL_ENABLE_FALLBACK`: set to `true` only for test sessions; the local model is tried after Gemini, OpenAI, and OpenRouter

The adapter sends requests to `/chat/completions` and checks `/models`, so this service exposes the matching OpenAI-compatible paths. Keep the endpoint behind TLS and authentication. Never put the token in browser JavaScript, HTML, a mobile app bundle, or source control.

The adapter exists in the separately maintained/deployed AI Gateway. Do not redeploy an older Edge Function source branch over the active gateway without first reconciling newer AI Gateway changes. Keep `NEW_STAR_MODEL_ENABLE_FALLBACK` unset for normal production provider routing. A successful `/healthz` alone does not prove the gateway can reach the service.

## Benchmark a deployed service

After the model is loaded and the service is reachable, run the standard-library benchmark from the repository root:

```sh
MODEL_TOKEN="$MODEL_TOKEN" python ai-inference/scripts/benchmark.py \
  --base-url https://your-inference-host.example \
  --requests 5 \
  --max-tokens 128
```

It checks health and model-list endpoints, then measures time-to-first-token and total streamed response time. The reported text-chunks/second is only a rough proxy, **not** tokenizer tokens/second. Compare only identical prompts, model files, quantization, hardware, and token limits. Do not paste the token into chat or commit it.

## Resource and production notes

CPU speed and memory use depend heavily on model size, quantization, context length, and host hardware. Start with a small quantized model and measure latency and RAM on the actual host. A CPU service is not unlimited and may be too slow for image/video generation or many concurrent users.

The Compose defaults use two CPU threads, zero GPU layers, and one concurrent generation to avoid overloading a small host. For GPU offload, install/build `llama-cpp-python` with CUDA support, provide a compatible NVIDIA driver and NVIDIA Container Toolkit, then set `N_GPU_LAYERS` to a positive number. The standard Dockerfile in this folder is CPU/OpenBLAS-oriented; setting GPU layers alone does not add CUDA support and the app will fail to load if the installed backend lacks it. Set `N_GPU_LAYERS=-1` only when you intentionally want llama.cpp to offload all eligible layers and have verified sufficient VRAM. Measure actual memory and latency before increasing concurrency. Production also needs TLS, firewall restrictions, token rotation, monitoring, resource limits, backups for configuration, and a plan for model updates.


## Gateway capability status

The authenticated Supabase AI Gateway accepts `{ "action": "status" }` and reports each modality separately under `capabilities`. A configured provider key means only that a secret exists; it does not guarantee billing, quota, provider reachability, or a successful generation. The current gateway reports text chat, image generation, speech generation, speech transcription, and a separate video-generation path when their provider paths exist. A configured key does not prove a successful generation. Live web search is explicitly unsupported until a real adapter and end-to-end tests are implemented.
