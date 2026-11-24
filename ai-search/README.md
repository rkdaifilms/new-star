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
