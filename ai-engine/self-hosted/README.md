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

## New Star private search index

The private PostgreSQL full-text index is created by migration
`supabase/migrations/202611250002_new_star_private_search.sql`. It is
service-role-only: browser roles have no table privileges or RPC execution.
The gateway checks this index first for both search requests and optional
chat retrieval. The index starts empty; add only content you have permission
to store and make searchable.

Prepare a UTF-8 JSONL file with one object per line:

```json
{"url":"https://docs.example.org/guide","title":"Guide title","source":"Example Docs","content":"Text that may be searched by New Star.","language":"en","published_at":null}
```

Run the ingestion utility from the repository root on a trusted machine:

```sh
SUPABASE_URL="https://YOUR_PROJECT.supabase.co" \
SUPABASE_SERVICE_ROLE_KEY="YOUR_SERVER_ONLY_SERVICE_ROLE_KEY" \
deno run --allow-read --allow-env=SUPABASE_URL,SUPABASE_SERVICE_ROLE_KEY scripts/new-star-search-ingest.ts documents.jsonl
```

The tool accepts HTTPS URLs only, rejects credentials in URLs, validates
content lengths and dates, rejects duplicate URLs within a batch, and limits
each batch to 500 documents. Never put the service-role key in browser code,
mobile apps, public repositories, or client-visible environment variables.
