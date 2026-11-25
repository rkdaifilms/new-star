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
environment variable, sets the chat slot count with `LLAMA_ARG_N_PARALLEL`, and
disables the built-in UI with `LLAMA_ARG_UI=false`. These names are intentional; do not rename them to
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

This server provides text chat only. An optional private speech worker is included separately under `speech-worker/`; image generation, video generation, web crawling, and model training still require separate models/workers. A successful container health check alone does not prove model inference; run the authenticated chat test above.

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

## Private speech recognition and synthesis worker

A separate, authenticated GPU worker is now included under `speech-worker/`. It provides:
- `POST /v1/audio/transcriptions`: Whisper-based automatic language detection and transcription for WebM, OGG, MP4/M4A, MP3, and WAV.
- `POST /v1/audio/speech`: Piper speech synthesis using a voice model explicitly configured for the requested language.
- `GET /health`: process health only; it does not prove the model weights or voices are installed.

The worker is optional and is not started with the default profile:

```sh
docker compose --profile speech build new-star-speech
docker compose --profile speech up -d new-star-speech
curl http://127.0.0.1:8090/health
```

Place the Whisper model cache in the persistent Docker volume (the first run downloads the selected model), and place licensed Piper `.onnx` voice files plus their matching `.onnx.json` config files in `models/`. Set `NEW_STAR_PIPER_VOICES` to a JSON map from language codes to exact filenames. Only configured voices are available; the worker returns an explicit error instead of silently speaking with the wrong language. The example Hindi voice filename is illustrative: verify that the corresponding licensed model and config are actually present before enabling it.

The speech worker binds to host loopback only and requires a separate random bearer key. For Supabase Edge Function access, put it behind an authenticated TLS reverse proxy/private network; never expose port 8090 directly. Configure the gateway integration with that private HTTPS endpoint and the same key only after its authenticated smoke tests pass. This worker is source code, not a running service: the GPU host, models, voice files, and deployment are still required.

### Connect the private speech worker to the gateway

After the worker is running behind a private TLS reverse proxy, configure these Supabase Edge Function secrets:

- `NEW_STAR_SPEECH_BASE_URL=https://YOUR_PRIVATE_SPEECH_HOST` (base URL only; do not add `/v1/audio`)
- `NEW_STAR_SPEECH_API_KEY` (same random key as the worker's `NEW_STAR_SPEECH_API_KEY`)

The gateway uses the authenticated `/v1/audio/transcriptions` and `/v1/audio/speech` endpoints only when `NEW_STAR_AI_MODE=self_hosted`. It returns a clear unavailable response when the worker or requested language voice is missing; it does not fall back to external speech providers in that mode. Creator Studio now sends the active language with speech requests. The STT worker detects the spoken language automatically. TTS works only for language codes with installed Piper voices in `NEW_STAR_PIPER_VOICES`.


## Private image generation worker

The optional `image` Compose profile runs a local Diffusers pipeline (default model: `stabilityai/sdxl-turbo`) on an NVIDIA CUDA GPU. It exposes only loopback port 8091 and serves authenticated PNG text-to-image requests. It currently supports 1K output and the configured aspect ratios; 2K/4K and image-to-image editing are deliberately rejected rather than silently downgraded.

1. Put the model cache on persistent storage and provision a GPU with enough VRAM for the selected model.
2. Set a unique `NEW_STAR_IMAGE_API_KEY` (at least 32 characters) in the private host's `.env`.
3. Start it with `docker compose --profile image build new-star-image` and `docker compose --profile image up -d new-star-image`.
4. Put an authenticated TLS reverse proxy in front of the loopback service. Never expose port 8091 directly to the public internet.
5. Configure Supabase Edge Function secrets `NEW_STAR_IMAGE_BASE_URL` (the HTTPS proxy base URL) and `NEW_STAR_IMAGE_API_KEY` (the same worker key). Verify the authenticated endpoint before enabling `NEW_STAR_AI_MODE=self_hosted`.

The worker downloads the configured open-weight model into its persistent cache on first load. This is local inference, not a New Star foundation model trained from scratch. Review the model license and usage restrictions before commercial use. The source code is committed but the image model has not been downloaded, GPU-tested, or deployed.
