# New Star private inference server

This service runs an open-weight GGUF language model on New Star-controlled GPU infrastructure. Inference requests go to this server; the runtime does not call Gemini, OpenAI, or OpenRouter.

## Requirements

- Linux GPU host with NVIDIA drivers and NVIDIA Container Toolkit
- Docker Engine and Docker Compose v2
- A GGUF instruct model whose license permits your intended use
- A long random API key shared securely with the Supabase Edge Function

The repository does not contain model weights, GPU compute, or a trained New Star foundation model. Those must be supplied and operated by the project owner. This is self-hosted inference of selected model weights, not training a foundation model from scratch.

## Start

1. Copy `.env.example` to `.env`.
2. Put the licensed GGUF file under `models/` and set its exact filename in `NEW_STAR_MODEL_FILE`.
3. Set `NEW_STAR_MODEL_API_KEY` to a unique random secret (at least 32 random bytes).
4. Run `bash verify.sh` on the GPU host. It checks Docker, NVIDIA runtime, the model file, starts the service, waits for health, and sends an authenticated inference request.
5. For a quick manual health check, use `curl http://127.0.0.1:8080/health`.
6. To manually verify authenticated inference:

```sh
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer $NEW_STAR_MODEL_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"local-model","messages":[{"role":"user","content":"Reply with: New Star private model is online."}],"temperature":0.2,"max_tokens":80}'
```

The service binds to loopback only. Put it behind a TLS reverse proxy or private network before connecting Supabase. Do not expose port 8080 publicly.

## Connect the New Star gateway

Set Supabase Edge Function secrets:

- `NEW_STAR_AI_MODE=self_hosted`
- `NEW_STAR_MODEL_BASE_URL=https://YOUR_PRIVATE_MODEL_HOST/v1`
- `NEW_STAR_MODEL_NAME=local-model`
- `NEW_STAR_MODEL_API_KEY=the-same-long-random-key`

The gateway's current chat adapter appends `/chat/completions` to the configured base URL. Therefore use the base path `/v1`, not the full `/chat/completions` URL.

Never put the model API key in frontend JavaScript. The Edge Function reads it server-side.

## Capabilities and limits

This server provides text chat only. Image generation, video generation, speech recognition, speech synthesis, web crawling, and model training require separate models/workers and are not implemented by this service. In self-hosted mode the gateway must report those capabilities as unavailable until their private workers are deployed. A successful container health check alone does not prove model inference; run the authenticated chat test above.
