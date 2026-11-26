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


## Deploy all private workers on the GPU host

After installing NVIDIA drivers, NVIDIA Container Toolkit, Docker Compose v2, the GGUF chat model, and the licensed Piper voice files named in `.env`, run this from the repository root:

```sh
cd ai-engine/self-hosted
cp .env.example .env
# Edit .env locally: use distinct random secrets, a real GGUF filename,
# and licensed Piper .onnx plus matching .onnx.json voice files.
bash deploy-workers.sh
bash verify.sh
```

`deploy-workers.sh` validates the host, distinct worker secrets, the local GGUF file, and every configured Piper voice/config pair; validates all Compose profiles; builds and starts chat plus speech, image, video, search, and vision workers; waits for each worker health endpoint; and confirms each protected worker rejects an invalid bearer token. It keeps all service ports bound to loopback. The script does not expose ports publicly, configure DNS/TLS, set Supabase secrets, or claim successful media generation. Image/video/vision model weights may be downloaded by their model runtimes on first inference and require sufficient GPU memory and disk space.

### HTTPS reverse proxy (Caddy)

The Supabase Edge Function requires public HTTPS endpoints; Compose deliberately binds all model/worker ports to loopback. The included `Caddyfile` terminates TLS and forwards each hostname only to its matching loopback service.

1. Create seven DNS A records (and AAAA records only if IPv6 is correctly configured) for model, embeddings, image, video, speech, search, and vision, all pointing to the GPU host.
2. Copy `caddy-domains.env.example` to a private environment file and replace every example hostname with a real domain you control.
3. Install Caddy on the GPU host, install the config and its systemd environment file, then validate/restart:
   ```sh
   cd ai-engine/self-hosted
   sudo install -m 644 ./Caddyfile /etc/caddy/Caddyfile
   sudo install -m 600 ./caddy-domains.env /etc/caddy/new-star-domains.env
   sudo mkdir -p /etc/systemd/system/caddy.service.d
   printf '%s\n' '[Service]' 'EnvironmentFile=/etc/caddy/new-star-domains.env' | sudo tee /etc/systemd/system/caddy.service.d/new-star.conf >/dev/null
   sudo systemctl daemon-reload
   set -a
   source ./caddy-domains.env
   set +a
   caddy validate --config /etc/caddy/Caddyfile
   sudo systemctl restart caddy
   sudo systemctl --no-pager --full status caddy
   ```
4. Confirm DNS resolves to the host and that inbound TCP 80/443 is available for automatic certificate issuance. Do not open ports 8080–8094 to the internet.
5. Set the matching `NEW_STAR_*_BASE_URL` values in the private host `.env` to the HTTPS hostnames (embedding URL must end in `/v1`), then run the Supabase secret configurator below.

Caddy automatically obtains and renews certificates for real DNS names. The example `.example` hostnames are documentation-only and will not issue certificates. The workers still require their own bearer API keys; HTTPS does not replace worker authentication.

### Configure the Supabase gateway secrets safely

Set `SUPABASE_PROJECT_REF` in the private host environment (or export it in the deployment shell), make sure the matching private HTTPS endpoints and distinct API keys are present in the local `.env`, then run:

```sh
cd ai-engine/self-hosted
set -a
source .env
set +a
export SUPABASE_PROJECT_REF=your20characterprojectref
bash configure-supabase-secrets.sh
```

The script validates HTTPS endpoint formats, project-ref format, model name, and minimum key lengths, then sets `NEW_STAR_AI_MODE=self_hosted` and the private chat/image/video/speech/search/vision secrets using the Supabase CLI. Embeddings are configured only when both embedding URL and key are supplied. It never prints secret values. The Supabase CLI must already be authenticated and the project ref must identify the intended project. Supabase accepting the secret upload is not a health or inference test; run the authenticated worker verifier and actual inference smoke tests separately.

After deploying a protected HTTPS reverse proxy for each worker, configure the matching server-side Supabase Edge Function secrets and run `python3 verify-workers.py --infer` plus `python3 verify-workers.py --video` to prove actual inference. Keep `.env` and all keys off Git and out of frontend code.

## Verify every private worker

The repository includes `verify-workers.py` to check the deployed worker HTTPS endpoints without printing secrets. Configure the five `NEW_STAR_*_BASE_URL` values and matching API keys in the environment of a trusted GPU/operations host. Each URL must point to a TLS reverse proxy that forwards to the worker's loopback-only Compose port; do not expose ports 8090–8094 publicly.

Run a health/configuration pass:

```sh
cd ai-engine/self-hosted
set -a
source .env
set +a
python3 verify-workers.py
```

Run actual image, vision, speech-synthesis, and private-index search inference (this downloads/loads model weights as needed and consumes GPU resources):

```sh
python3 verify-workers.py --infer
```

For crawler verification, set `NEW_STAR_SMOKE_CRAWL_URL` to a public page you are authorized to index. The crawler validates DNS answers as globally routable and pins those exact IPs in its HTTP/HTTPS connection pool before sending a request; HTTPS retains hostname-based SNI and certificate verification, and the connected peer IP is checked again before page content is read. It accepts only standard HTTP/HTTPS ports, follows same-origin links only on the original scheme and port, and is capped at five pages per submitted root URL. To test speech synthesis in a different installed voice language, set `NEW_STAR_SMOKE_SPEECH_LANGUAGE` to its configured ISO language code. To query a different indexed phrase, set `NEW_STAR_SMOKE_SEARCH_QUERY`.

Video generation is a separate, expensive six-second GPU job and is not run by the default checks:

```sh
python3 verify-workers.py --video
```

The verifier checks that protected endpoints reject an invalid bearer token, never follows HTTP redirects (so credentials cannot be forwarded to another host), and does not print configured secrets. Its tests are run in AI Engine CI. The vision smoke test uses a valid PNG fixture that meets the worker's request-size validation, and video output validation checks the MP4 `ftyp` signature. The video test waits up to `NEW_STAR_SMOKE_VIDEO_TIMEOUT` seconds (default 1800). A successful health check only proves that the worker responds; only the `--infer`/ `--video` runs prove actual model inference. A skipped worker is not counted as passed. Use `--require-all` in a release environment where every worker must be deployed.

## Connect the New Star gateway

Set Supabase Edge Function secrets:

- `NEW_STAR_AI_MODE=self_hosted`
- `NEW_STAR_MODEL_BASE_URL=https://YOUR_PRIVATE_MODEL_HOST/v1`
- `NEW_STAR_MODEL_NAME=local-model`
- `NEW_STAR_MODEL_API_KEY=the-same-long-random-key`

The gateway's current chat adapter appends `/chat/completions` to the configured base URL. Therefore use the base path `/v1`, not the full `/chat/completions` URL.

Never put the model API key in frontend JavaScript. The Edge Function reads it server-side.

## Capabilities and limits

This Compose project contains separate optional workers for private text chat, embeddings, speech recognition/synthesis, image generation, video generation, web crawling/search, and image understanding. They are separate processes and model stacks, not one model that magically provides every capability.

- Chat uses the configured GGUF model through llama.cpp.
- Embeddings use a separately configured 384-dimensional model.
- Speech uses Faster-Whisper for transcription and Piper voice files for synthesis; only installed language voices work.
- Image generation currently accepts 1K output only.
- Video generation currently accepts 480p, six-second clips in 16:9 or 9:16.
- Search queries the private index populated by explicitly submitted public URLs; it is not general live-web search.
- Vision uses the configured local vision-language model to analyze an uploaded image.

All worker source code and Compose profiles are present, but this does not mean the services are deployed. Each worker still requires a compatible GPU/CPU host, licensed model weights or voice files, unique API key, protected HTTPS reverse proxy, server-side Supabase secrets, and successful authenticated inference tests. Run `python3 verify-workers.py` for health/auth checks, `python3 verify-workers.py --infer` for image/vision/speech/search inference, and `python3 verify-workers.py --video` for a real video-generation job. A successful container health check alone does not prove model inference.

The training directory provides a QLoRA adapter fine-tuning pipeline for an open-weight base model. It does not train a New Star foundation model from random initialization; no training run or New Star model weights are included.

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


## Private video generation worker

The optional `video` Compose profile adds a local Diffusers/CogVideoX worker. It uses the open-weight `THUDM/CogVideoX-2b` model, runs inference on a CUDA GPU, and returns MP4 jobs through an authenticated API. The currently implemented, deliberately bounded output is **480p and 6 seconds** in landscape 16:9 or portrait 9:16. The Creator Studio controls expose only those verified-by-code settings; 720p, longer clips, and editing are not silently advertised as available.

1. Provision a CUDA GPU host with enough system RAM, GPU memory, and persistent disk for the model cache and generated jobs. CogVideoX model weights are large; first model load downloads them into the persistent cache.
2. Set a unique `NEW_STAR_VIDEO_API_KEY` of at least 32 characters in the private host's `.env`.
3. Build and start the worker: `docker compose --profile video build new-star-video`, then `docker compose --profile video up -d new-star-video`.
4. Verify the worker health endpoint locally, then put an authenticated TLS reverse proxy/private network in front of loopback port 8092. Never expose the raw worker port publicly.
5. Configure Supabase Edge Function secrets `NEW_STAR_VIDEO_BASE_URL` (HTTPS proxy base URL) and `NEW_STAR_VIDEO_API_KEY` (same worker key). Verify an authenticated test job and Supabase Storage upload before enabling self-hosted mode.

Jobs and outputs are stored in a persistent Docker volume. If the worker restarts during an active generation, the interrupted job is marked failed on restart rather than falsely remaining in progress. The gateway stores job ownership in `ai_video_jobs`, polls the private worker, uploads the finished MP4 into the private `new-star-ai-videos` bucket, and returns a short-lived signed URL. This is local inference with an open-weight model, not a New Star foundation model trained from scratch. Review the model's license and commercial-use terms. The source is committed but model weights have not been downloaded, GPU inference has not been tested, and no worker or Supabase secret has been deployed.


## Private web crawler and search index

The optional `search` profile provides a private crawler and persistent SQLite FTS5 index. It indexes pages explicitly crawled by the operator; it is not a general live-web search engine and does not invent results for pages it has never indexed. The search worker is CPU-only and uses a lightweight Python runtime; it does not need CUDA, PyTorch, or a GPU reservation.

1. Set a unique `NEW_STAR_SEARCH_API_KEY` (at least 32 characters).
2. Build/start with `docker compose --profile search build new-star-search` and `docker compose --profile search up -d new-star-search`.
3. Put loopback-only port 8093 behind an authenticated TLS reverse proxy/private network.
4. Set Supabase Edge Function secrets `NEW_STAR_SEARCH_BASE_URL` and `NEW_STAR_SEARCH_API_KEY`.
5. Call the authenticated gateway action `crawl` with a public URL to index a page; then use `search` to query the private index.

The crawler accepts public HTTP/HTTPS hosts, rejects local/private/reserved or mixed public/private DNS answers, verifies the connected peer IP before reading response content, does not follow redirects, and accepts HTML/plain text only. It limits each page to 2 MiB, each crawl to 10 MiB of downloaded response data, discovered links to 100 per page, pages to five per submitted root URL, and extracted text to 100,000 characters per page. It also removes script/style/navigation/footer content. Search uses SQLite FTS5 and returns source URLs, snippets, and fetch timestamps. It follows links only on the original host, scheme, and port; it does not crawl third-party domains. The worker is source code only until deployed and tested.

## Private image understanding (vision)

The optional `vision` profile runs `HuggingFaceTB/SmolVLM2-2.2B-Instruct` locally on a CUDA GPU. It accepts authenticated base64 PNG/JPEG/WebP images up to 6 MiB and 20 megapixels, checks format and dimensions before full decoding, scales them to a bounded 1280-pixel maximum dimension, and returns a text answer. In self-hosted mode, image data is not sent to external providers.

1. Set a unique `NEW_STAR_VISION_API_KEY` (at least 32 characters).
2. Build/start with `docker compose --profile vision build new-star-vision` and `docker compose --profile vision up -d new-star-vision`.
3. Put loopback-only port 8094 behind an authenticated TLS reverse proxy/private network.
4. Set Supabase Edge Function secrets `NEW_STAR_VISION_BASE_URL` and `NEW_STAR_VISION_API_KEY`.

Model weights are downloaded to the persistent cache on first use. This is local inference using an open-weight model, not a New Star foundation model trained from scratch. Review the model license and hardware requirements. Neither worker has been deployed or GPU-tested.


## Private New Star model fine-tuning

The separate `ai-engine/training` directory contains a local QLoRA fine-tuning pipeline for adapting an Apache-2.0 open-weight Qwen2.5-3B-Instruct base model. This is a path toward a New Star-tuned model and private inference; it is **not** training a foundation model from scratch. The base model remains a third-party model and its license/model card must be reviewed before commercial use.

### 1. Prepare a private dataset

Create a UTF-8 JSONL file. Each line is one conversation:

```json
{"messages":[{"role":"user","content":"Explain how to make a video storyboard."},{"role":"assistant","content":"A storyboard is a sequence of visual panels..."}]}
```

Keep only data you have rights and permission to use. Do not train on private user conversations, credentials, personal records, or scraped content without a lawful basis and appropriate consent. The validator rejects malformed turns, likely API keys/tokens/passwords, excessive lengths, and duplicate conversations.

Run the validator:

```sh
python3 ai-engine/training/prepare_dataset.py --input ./private-training.jsonl --output-dir ./prepared-dataset
```

It writes deterministic `train.jsonl`, `validation.jsonl`, and `dataset-report.json`. Inspect the report and the split files before training. Keep all dataset files private and outside public Git repositories.

### 2. Train the adapter on a CUDA GPU

Install `ai-engine/training/requirements.txt` in an isolated Python environment with a compatible NVIDIA driver/CUDA runtime, or build the provided training image. The default training script requires a CUDA GPU and uses 4-bit NF4 quantization, gradient checkpointing, LoRA rank 16, and a bounded context length.

```sh
python3 ai-engine/training/train_lora.py --dataset-dir ./prepared-dataset --output-dir ./new-star-qwen-adapter --epochs 2 --batch-size 1 --gradient-accumulation 16 --max-seq-length 2048
```

The script evaluates each epoch, retains the best checkpoint, and saves the LoRA adapter and a training report. GPU memory requirements vary by model, context length, and batch settings. Start with a small private dataset and verify evaluation loss and sample outputs before using the adapter.

### 3. Model governance before production

Review the dataset provenance, consent, copyright/license status, privacy, harmful-output testing, multilingual quality, and evaluation results. A fine-tuned adapter is not automatically safe or production-ready. Merge/quantize/convert the adapter to the llama.cpp GGUF format in a separately tested model-release pipeline before updating `NEW_STAR_MODEL_FILE`. Do not replace the live model file until the candidate passes evaluation and rollback is available. No training has been run and no adapter/model weights are included in this repository.
