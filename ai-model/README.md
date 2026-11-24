# New Star self-hosted model runtime

This directory is the deployment blueprint for New Star's own open-weight chat model.
It is separate from the Supabase Edge Function: Supabase remains the authenticated
gateway and this runtime serves model inference.

## Current status

- The gateway adapter is implemented and deployed in Supabase AI Gateway v83.
- The adapter expects an OpenAI-compatible `/chat/completions` endpoint.
- The gateway status action now performs non-billable credential/access probes for configured providers. Gemini and OpenAI use model-list endpoints so checks do not depend on one specific model; it does not run paid inference or verify inference quota.
- This directory contains the GPU runtime deployment files.
- No GPU host is provisioned or running yet. Therefore New Star's independent model
  is **not online or tested**.

## Runtime

The Compose file uses vLLM's OpenAI-compatible server and defaults to
`Qwen/Qwen3-8B`. The model weights are downloaded by the runtime on first start.
Actual GPU memory requirements depend on model, precision, context length, and
concurrency; check the model and vLLM compatibility documentation before selecting
a host. A compatible NVIDIA GPU and NVIDIA Container Toolkit are required.

1. On a GPU machine with Docker Compose and NVIDIA Container Toolkit, copy
   `.env.example` to `.env`.
2. Replace `NEW_STAR_MODEL_API_KEY` with a long random secret. Do not reuse a
   Supabase key or any provider key.
3. Review the model's license and hosting requirements before accepting its weights.
4. Run `docker compose up -d` from this directory and inspect container logs.
5. Keep port 8000 bound to localhost. Put an HTTPS reverse proxy or private tunnel
   with authentication in front of it; do not expose the raw vLLM port publicly.
6. Configure the Supabase Edge Function secrets:
   - `NEW_STAR_MODEL_BASE_URL`: the HTTPS base URL ending in `/v1`
   - `NEW_STAR_MODEL_NAME`: `new-star-model`
   - `NEW_STAR_MODEL_API_KEY`: the same random secret configured in this runtime
7. After secrets are configured, test with a signed-in New Star account and inspect
   Supabase function logs. The gateway status response checks the model-list endpoint when configured; it does not run paid inference.

## Security and cost

- Never put model credentials in browser JavaScript or commit a real `.env` file.
- Keep the inference service behind TLS and authentication. Use firewall rules so
  only the gateway's trusted egress path can reach it where the hosting platform
  supports that restriction.
- GPU hosting, storage, and bandwidth may incur charges. Do not provision paid
  infrastructure without approval from the account owner.
- The default model is a configurable starting point, not a claim that this exact
  model is already running or has passed quality evaluation.
