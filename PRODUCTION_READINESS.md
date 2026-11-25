# New Star AI — production readiness and operator runbook

This repository contains several deployable services, not one self-contained trained foundation model. Complete this checklist in the target environment before calling a deployment production-ready.

## Service topology

- **Hosted gateway:** Supabase Edge Function `ai-gateway`; authenticates the application request and routes to configured providers.
- **Local text inference:** `ai-inference`; OpenAI-compatible API. The currently configured GGUF artifact is an external model distribution, not New Star-trained weights.
- **Orchestration API/workers:** `ai-engine`; model registry, request routing and SQLite-backed local job queue. Current queue/rate-limit behavior is single-host; it is not a distributed multi-region cluster.
- **Lexical search:** `ai-search/new_star_search.py`; SQLite FTS5 plus an allowlist-only crawler.
- **Semantic search:** `ai-search/vector_server.py`; FastEmbed multilingual embeddings and Supabase Postgres pgvector.
- **Experimental foundation model:** `ai-foundation`; New Star decoder-only Transformer training/evaluation code. A useful production checkpoint does not exist until authorized, sufficiently large, licensed training data is supplied and training/evaluation is completed.
- **Image, speech and video:** gateway adapters currently call configured external providers. They are not New Star-owned trained multimodal weights.

## Required environment configuration

Set secrets only in the server/hosting secret manager. Never commit `.env` files or put service credentials in browser code.

| Service | Required configuration |
| --- | --- |
| Supabase gateway | Provider credentials that the operator has rights to use; `NEW_STAR_MODEL_BASE_URL`, `NEW_STAR_MODEL_NAME`, `NEW_STAR_MODEL_API_KEY` only when routing to a self-hosted model; `NEW_STAR_SEARCH_BASE_URL` and `NEW_STAR_SEARCH_API_KEY` for search |
| AI engine | `NEW_STAR_AI_SERVER_TOKEN`; persistent queue volume; validated model registry |
| Inference | `MODEL_TOKEN`; `MODEL_PATH`; verified `MODEL_SHA256`; bounded `N_CTX`, `N_BATCH`, `MAX_TOKENS`, `MAX_CONCURRENT` |
| Search | `NEW_STAR_SEARCH_API_KEY`; `NEW_STAR_SEARCH_ALLOWED_DOMAINS`; persistent SQLite volume |
| Vector search | `NEW_STAR_VECTOR_DATABASE_URL` using a restricted server-side database role; same search bearer secret; apply the pgvector migration first |
| Foundation training | Read-only licensed corpus mount; writable artifact mount; explicit `--device` and `--precision`; provenance manifest for JSONL corpora |

## Deployment sequence

1. Review the exact branch/commit and CI results. Do not deploy an unreviewed branch.
2. Apply Supabase migrations in a staging project. Verify the vector extension schema and service-role-only table access.
3. Create a restricted Postgres role for vector search. Use TLS/database SSL and a connection pooler appropriate to the Supabase plan. Do not use a browser-exposed key or privileged owner credential.
4. Build images from pinned repository revisions. Scan dependencies and images; record image digests and the model artifact SHA-256.
5. Start lexical search and vector search on a private network. Keep ports bound to loopback/private interfaces; expose only through an authenticated HTTPS reverse proxy.
6. Start inference and the orchestration API/workers. Keep model endpoints private; use separate service tokens and rotate them on a schedule.
7. Configure Supabase Edge Function secrets. Never expose upstream provider keys, vector DB URLs or service tokens to the client.
8. Run smoke tests: service health, auth rejection, one short completion, one lexical search, one vector search, one gateway request, and one job lifecycle.
9. Verify backups and restore procedure before accepting production traffic.

## GPU validation

Install a supported NVIDIA driver and NVIDIA Container Toolkit on a host with an actual NVIDIA GPU. Verify `nvidia-smi` and Docker GPU visibility first. Then run:

```bash
docker compose -f ai-foundation/docker-compose.yml --profile gpu build train-gpu
docker compose -f ai-foundation/docker-compose.yml --profile gpu run --rm train-gpu --help
docker compose -f ai-foundation/docker-compose.yml --profile gpu run --rm --entrypoint python3 train-gpu gpu_validate.py --device cuda --report-json /artifacts/gpu-validation.json
```

The last command overrides the training entrypoint so the GPU smoke test runs instead of starting a training job. The validation script fails when CUDA is unavailable and records device, precision, peak allocated/reserved memory and synthetic-token throughput. GPU training Compose enables activation checkpointing to reduce activation memory. This is a smoke test, not a model-quality benchmark. Run a separate licensed-corpus training experiment and record validation loss before production use.

For inference, build `ai-inference/Dockerfile.cuda`, enable the Compose `gpu` profile, verify `/healthz` reports the loaded model and GPU offload requested, then run `ai-inference/scripts/benchmark.py` with `MODEL_TOKEN` in the environment. A GPU layer request alone does not prove that the model is actually offloaded; inspect runtime logs and GPU utilization.

## Multilingual evaluation

Use a language-tagged JSONL corpus with separate `train` and `validation` records per language. Keep validation text disjoint from training text. Run the corpus audit, train, then run `evaluate.py --data-format jsonl` against held-out validation data. Store per-language cross-entropy/perplexity reports and compare regressions across runs. The included CI regression set covers eight Unicode scripts/languages and verifies that the canonical gateway catalog (90+ language entries) can be checked for complete validation-corpus coverage. CI uses synthetic smoke data only; it is not a claim of translation quality or proficiency. For held-out language-model metrics, provide reviewed, rights-cleared validation text for every catalog language and run `python evaluate.py --checkpoint artifacts/newstar.pt --data validation.jsonl --data-format jsonl --require-all-supported-languages --report-json artifacts/multilingual-report.json`. For generated-output quality, provide a separate reviewed JSONL with `language`, `prompt`, and `reference` per case, then run `python generation_eval.py --checkpoint artifacts/newstar.pt --cases generation-cases.jsonl --require-all-supported-languages --report-json artifacts/generation-quality.json`. The latter reports exact match and chrF-style character n-gram F1; human bilingual review and task-specific factual checks remain required.

## Operations, security and monitoring

- Terminate TLS at a maintained reverse proxy; enforce request-size, timeout, connection and rate limits there.
- Keep internal service ports private. Restrict CORS at the gateway; do not enable wildcard origins for authenticated APIs.
- Use separate least-privilege credentials for Supabase, model inference, search and workers.
- Rotate tokens; redact authorization headers, prompts, personal data and provider payloads from logs.
- Monitor request count, latency, error rate, queue depth/age, worker restarts, model load state, CPU/RAM/GPU memory, disk, SQLite WAL growth, crawler failures and Postgres connection/slow-query metrics.
- Alert on failed health checks, sustained 5xx, queue saturation, low disk, backup failure and GPU OOM.
- Back up SQLite volumes and database data; test restoration. Define retention and deletion procedures for prompts, logs and indexed content.
- Crawl only sources with permission and respect robots.txt and site terms.
- Keep a rollback image digest and previous database migration plan. Test rollback in staging first.

## Known production blockers / honest status

- The two pgvector/hybrid migrations have been applied to the connected New Star Supabase project and verified with a temporary insert/query/delete smoke test. The semantic index is still empty (no authorized crawl/backfill yet), and no public HTTPS route or production service deployment has been performed.
- No real NVIDIA host validation is possible from ordinary GitHub Actions CPU runners. The CUDA smoke test must be run on the operator's GPU host.
- The own foundation Transformer is randomly initialized until trained. It is not currently comparable to ChatGPT/Gemini and cannot be described as a completed frontier model.
- Multimodal generation is provider-adapter integration today; New Star-owned image/video/audio foundation models and training datasets are not present.
- The current AI engine's SQLite queue and process-local controls are single-host. Multi-host production requires shared durable queue/storage, distributed rate limiting, tenant isolation, and operational testing.
- Do not advertise unlimited usage or guaranteed availability. Set budgets, quotas and capacity limits before enabling public access.
