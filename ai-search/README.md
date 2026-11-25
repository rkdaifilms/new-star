# New Star Search

A self-hosted search index for New Star. It provides a SQLite FTS5 index, a bounded HTTPS crawler, robots.txt enforcement, domain allowlisting, result ranking, and a bearer-authenticated JSON search API. It stores indexed text for retrieval but returns only a short snippet, title, source host, and URL.

## Configure

Set the following environment variables on the search host:

- NEW_STAR_SEARCH_API_KEY: long random bearer secret required by POST /search.
- NEW_STAR_SEARCH_ALLOWED_DOMAINS: comma-separated domain allowlist for crawling, for example example.org,docs.example.net.
- NEW_STAR_SEARCH_DB: persistent SQLite database path (default var/new-star-search.sqlite3).
- NEW_STAR_SEARCH_USER_AGENT: optional crawler user-agent string.
- NEW_STAR_SEARCH_MAX_PAGE_BYTES: maximum HTML page download size (default 2,000,000).
- NEW_STAR_SEARCH_FETCH_TIMEOUT: HTTP timeout seconds (default 12).

Do not crawl domains without authorization. The crawler checks robots.txt, follows only allowlisted hosts, requires HTTPS on port 443, blocks non-public DNS destinations, does not execute JavaScript, and caps page count, depth, response size, and request rate. A site may still prohibit automated indexing in its terms; obtain permission where required.

## Index pages

Run from this directory:

    python new_star_search.py --db var/new-star-search.sqlite3 crawl --seed https://docs.example.org/ --max-pages 100 --delay-seconds 1.5

The seed host and every discovered link must match NEW_STAR_SEARCH_ALLOWED_DOMAINS. Crawl results report visited, indexed, and failed counts.

## Run API

    export NEW_STAR_SEARCH_API_KEY='set-a-long-random-secret'
    python new_star_search.py --db var/new-star-search.sqlite3 serve --host 0.0.0.0 --port 8081

POST /search with Authorization: Bearer token and JSON {"query":"your words","language":"en","limit":5}. The service returns {"ok":true,"results":[{"title":"...","url":"https://...","snippet":"...","source":"..."}]}. GET /healthz reports service readiness and indexed page count. The gateway adapter expects this response shape and validates every result URL again.

## Docker

Build and run with a persistent volume:

    docker build -t new-star-search .
    docker run -d --name new-star-search -p 127.0.0.1:8081:8081 -e NEW_STAR_SEARCH_API_KEY="$NEW_STAR_SEARCH_API_KEY" -v new-star-search-data:/var/lib/new-star-search new-star-search

Place the API behind a trusted HTTPS reverse proxy before connecting the hosted Supabase gateway. Configure NEW_STAR_SEARCH_BASE_URL and NEW_STAR_SEARCH_API_KEY only in Supabase Edge Function secrets. Never put the search API key in frontend JavaScript.

## Limits

This is a focused allowlist-based crawler and FTS index, not a complete global search engine. It does not crawl the open web indiscriminately, render JavaScript, guarantee freshness, or provide multi-node distributed indexing. The database and API are single-host. Back up the database and monitor crawl errors before production use.


## Multilingual semantic retrieval (pgvector)

The lexical SQLite index remains available on port 8081. A separate semantic retrieval service runs on port 8082 and stores vectors in Supabase Postgres using pgvector.

1. Apply both migrations in order: `supabase/migrations/20261125000000_new_star_vector_search.sql` and `supabase/migrations/20261125010000_new_star_hybrid_search.sql`.
2. Configure `NEW_STAR_SEARCH_API_KEY` and `NEW_STAR_VECTOR_DATABASE_URL` in the search host environment. The database URL must use a restricted server-side role; never expose it to browser code.
3. Start the vector service with `docker compose up -d new-star-vector-search`. The service downloads and caches the multilingual FastEmbed ONNX model on first start; its health check verifies database connectivity.
4. Crawl only domains for which indexing is authorized using the existing `crawl` command.
5. Backfill the SQLite pages into pgvector:
   `docker compose run --rm --entrypoint python new-star-vector-search vector_sync.py --sqlite-db /var/lib/new-star-search/index.sqlite3`
6. Expose port 8082 through an HTTPS reverse proxy with authentication preserved. Configure the Supabase Edge Function's `NEW_STAR_SEARCH_BASE_URL` to that HTTPS origin to route RAG through hybrid vector/full-text retrieval and reciprocal-rank fusion.

The encoder is the pretrained `intfloat/multilingual-e5-small` baseline (384 dimensions), not a New Star-trained embedding model. It is local at inference time after model download. The retrieval stage independently fetches vector and PostgreSQL full-text candidates, fuses them with reciprocal-rank fusion (RRF), then reranks using cosine similarity, Unicode token overlap and the fused rank score. Source filtering is applied to both candidate sets. A learned cross-encoder reranker is not included; the current reranker is deterministic and does not require an external model.

### Vector API contract

- `GET /healthz`: checks the live Postgres connection.
- `POST /search`: bearer-authenticated JSON `{"query":"...","limit":5,"source":"optional.example"}`.
- Response includes `ok`, `retrieval: "hybrid-vector-fts-rrf"`, and `results` with `title`, `url`, `source`, `snippet`, `similarity`, and `rerank_score`.

Run the service unit tests with `python -m unittest discover -s tests -v` from this directory. The CI container build validates dependencies and the Compose manifest; a live Supabase migration, model download, populated index, and real semantic query still require the authorized deployment environment.
