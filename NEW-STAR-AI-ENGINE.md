# New Star AI Engine

## Current verified state

- Supabase Edge Function `ai-gateway` is **ACTIVE, version 127**.
- The gateway contains an application-owned orchestration layer for authentication, rate limits, chat routing, conversation persistence, API-key management, language policy, search, and media-provider adapters.
- The separate Creator Studio workspace is `new-star-ai.html`; it shares New Star authentication and does not require a second AI account.
- The current feature work is in pull request #6 on branch `new-star-ai-engine-foundation`. It has not been merged into production.
- Vercel's latest deployment check reported a daily build-rate limit. No new frontend deployment is claimed.

## One canonical self-hosted runtime

The only model runtime deployment directory is `ai-engine/self-hosted/`. The duplicate `ai-model/` vLLM deployment blueprint was removed so the repository has one source of truth.

- `compose.yaml` runs the private llama.cpp chat server, optional 384-dimensional embeddings, and separate optional Image, Video, Speech, Search/Crawler, and Vision workers.
- All worker ports bind to loopback only: chat 8080, embeddings 8081, speech 8090, image 8091, video 8092, search 8093, and vision 8094.
- `verify-workers.py` checks HTTPS health and rejects invalid bearer tokens, refuses redirects, and can run real media/search inference after workers are deployed.
- `ai-engine/training/` contains a private dataset validator and CUDA QLoRA fine-tuning pipeline for an open-weight base model; it is not pretraining a foundation model from scratch.
- `verify.sh` checks Docker, NVIDIA runtime, model file presence, service health, and authenticated text inference.
- `verify-embeddings.sh` checks embedding service health and verifies exactly 384 numeric dimensions.
- Model weights are not stored in Git. The owner must supply legally licensed model files and a compatible NVIDIA GPU host.
- The embedding endpoint must be exposed to Supabase only through a protected HTTPS reverse proxy/private network; Supabase cannot call the GPU host's loopback address.

This is self-hosted inference of open-weight models. It is **not** a New Star foundation model trained from scratch. No New Star-owned foundation-model training dataset, GPU training cluster, or completed training run exists yet.

## Self-hosted routing and isolation

When the Supabase secret `NEW_STAR_AI_MODE=self_hosted` is set, the gateway's chat provider plan contains only the configured New Star-hosted model. It does not fall back to Gemini, OpenAI, or OpenRouter.

In that mode:
- Private Search uses the existing New Star search index and its private embedding endpoint.
- If the private index/embedding path is unavailable, Search returns an explicit unavailable response rather than using an external search worker.
- Chat retrieval does not fall back to a separately hosted external search worker.
- Image generation, video generation, speech recognition/synthesis, and image understanding use the private worker adapters. Worker source code is present, but each worker remains unavailable until its GPU host, model/voice weights, HTTPS route, API key, and deployment are configured.

**Production caveat:** The secret `NEW_STAR_AI_MODE=self_hosted` has not been confirmed active in the live Supabase environment. Do not assume external providers are disabled in production until the secret is set and verified. Do not remove the existing provider configuration before the private model is provisioned and tested.

## Private Search data path

New Star reuses the existing `public.new_star_search_chunks` table, its 384-dimensional vectors, HNSW index, and GIN full-text index. No duplicate search document table is required.

- `search_new_star_private_index` combines full-text and vector candidates.
- `replace_new_star_search_chunks` replaces all chunks for each submitted source in one transaction, preventing stale trailing chunks after a source becomes shorter.
- Both private RPCs are restricted to `service_role`; anonymous and authenticated browser roles cannot read the private chunk table or execute the RPCs.
- `scripts/new-star-search-ingest.ts` accepts UTF-8 JSONL source documents, chunks the content, obtains 384-dimensional embeddings, and calls the atomic replacement RPC.
- The index is empty until the owner ingests authorized source content. A healthy search RPC returns a successful empty result set when there are no matching documents; only an RPC/configuration failure is reported as the private index being unavailable.

The GPU host's embedding service is configured separately from the chat model. Supabase secrets `NEW_STAR_EMBEDDING_BASE_URL`, `NEW_STAR_EMBEDDING_API_KEY`, and optionally `NEW_STAR_EMBEDDING_MODEL_NAME` are required for semantic retrieval. Without an embedding endpoint, full-text retrieval remains available.

## Other connected capabilities and limits

The existing gateway also contains provider adapters for image generation, image understanding, speech input/output, web search, and Vertex AI video jobs. Those integrations are not equivalent to a private New Star model.

- Provider credentials are server-side, not embedded in browser JavaScript.
- Provider status probes do not prove quota, inference, or successful media generation.
- The Vertex AI video path is provider-connected but still requires a real authorized end-to-end test: job completion, private MP4 storage, and owner-only playback.
- Private image, video, vision, transcription, speech, and crawler workers have source code in this branch but are not deployed or end-to-end tested.
- Do not claim an image, video, search result, upload, or playback succeeded unless the corresponding real operation was verified.

## Verification and release gates

- Supabase confirms `ai-gateway` is ACTIVE at version 127.
- The hybrid search RPC was smoke-tested with temporary lexical and semantic rows; the rows were removed.
- The atomic replacement RPC was smoke-tested: two old chunks were replaced by one new chunk, stale data disappeared, and an invalid 383-dimensional vector was rejected. The transaction was rolled back.
- Database permissions were checked: only `service_role` can execute the private search and replacement RPCs.
- Latest branch CI passed: 38 Deno regression tests, 9 Python worker-verifier unit tests, Python syntax validation, frontend syntax, Supabase Edge Function type-check, runtime configuration, gateway action/auth, and Creator Studio checks. See the latest runs in PR #6.
- The latest Vercel status is a daily build-rate-limit failure; this does not change the independently deployed Supabase gateway.

Do not mark the AI platform complete until a GPU host and licensed model weights are provisioned, authenticated private chat and embeddings pass end-to-end tests, the self-hosted secret is confirmed active, and each required private media worker is implemented and tested.

## Operational rule

Keep four states distinct: configured, deployed, provider-connected, and end-to-end tested. Never describe a planned or configured capability as running.