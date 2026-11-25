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

The Compose file maps the project key to llama.cpp's documented `LLAMA_API_KEY`
environment variable and sets the chat slot count with
`LLAMA_ARG_N_PARALLEL`. These names are intentional; do not rename them to
`LLAMA_ARG_API_KEY` or `LLAMA_ARG_PARALLEL`. The runtime flags are documented
in the upstream [llama.cpp server options](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md).

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

## Canonical runtime

This directory is the repository's single self-hosted model runtime. The old
`ai-model/` vLLM blueprint was removed to avoid maintaining two competing
chat-model configurations. Chat and the optional embedding service are defined
together in `compose.yaml`.

## New Star private search index

New Star already had `public.new_star_search_chunks` with `vector(384)`,
HNSW and GIN indexes. The new private search path reuses that existing table;
it does not create a second document store. Migration
`supabase/migrations/20261125125524_reuse_existing_new_star_search_chunks.sql`
adds language and chunk metadata and installs a service-role-only full-text
search RPC. Anonymous and signed-in browser roles cannot read the chunks or
execute the private search function.

The search index is empty until authorized content is ingested. The ingestion utility calls the service-role-only `replace_new_star_search_chunks` RPC, defined by migrations `20261125131109_replace_new_star_search_chunks_rpc.sql` and `20261125131209_harden_search_chunk_replacement_rpc.sql`, which atomically deletes previous chunks for each submitted source and inserts its replacement set. This prevents stale trailing chunks when a source document becomes shorter. Prepare a
UTF-8 JSONL file with one source document per line:

```json
{"url":"https://docs.example.org/guide","title":"Guide title","source":"Example Docs","content":"Text that may be searched by New Star.","language":"en","published_at":null}
```

The optional `new-star-embeddings` Compose service runs a dedicated
384-dimensional embedding model on loopback port 8081. Place a legally
licensed, compatible E5-small-v2 GGUF file in `models/`, configure
`NEW_STAR_EMBEDDING_MODEL_FILE` and a separate random
`NEW_STAR_EMBEDDING_API_KEY`, then start it with:

```sh
docker compose --profile embeddings up -d new-star-embeddings
bash verify-embeddings.sh
```

Run the ingestion utility from the repository root on a trusted machine:

```sh
SUPABASE_URL="https://YOUR_PROJECT.supabase.co" \
SUPABASE_SERVICE_ROLE_KEY="YOUR_SERVER_ONLY_SERVICE_ROLE_KEY" \
NEW_STAR_EMBEDDING_BASE_URL="http://127.0.0.1:8081/v1" \
NEW_STAR_EMBEDDING_API_KEY="YOUR_PRIVATE_EMBEDDING_KEY" \
deno run --allow-read --allow-env=SUPABASE_URL,SUPABASE_SERVICE_ROLE_KEY,NEW_STAR_EMBEDDING_BASE_URL,NEW_STAR_EMBEDDING_API_KEY scripts/new-star-search-ingest.ts documents.jsonl
```

The tool validates HTTPS source URLs, rejects URL credentials and duplicate
source URLs, chunks the text with overlap, obtains one 384-dimensional vector
per chunk from the private embedding worker, and limits each batch to 100
source documents and 200 chunks. Never expose the Supabase service-role key or
embedding API key in browser code, mobile apps, public repositories, or
client-visible environment variables.

For semantic retrieval from the hosted Supabase Edge Function, configure
server-side Edge Function secrets `NEW_STAR_EMBEDDING_BASE_URL`,
`NEW_STAR_EMBEDDING_API_KEY`, and optionally
`NEW_STAR_EMBEDDING_MODEL_NAME`. The gateway accepts only an HTTPS endpoint
without URL credentials, query strings, or fragments. The loopback URL above
is for the ingestion utility running on the GPU host; Supabase cannot call
that loopback address. Expose the embedding service only through a protected
TLS reverse proxy/private network with the separate embedding API key, and
never expose the raw model port publicly. If the embedding worker is not
configured, the gateway continues to use the existing full-text GIN index.
