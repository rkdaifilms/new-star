"""Authenticated HTTP API for New Star's pgvector semantic retrieval."""
from __future__ import annotations

import hmac
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from vector_backend import PgVectorSearch


def make_handler(store, api_key: str):
    class Handler(BaseHTTPRequestHandler):
        server_version = "NewStarVectorSearch/1.0"

        def log_message(self, fmt, *args):
            print("[new-star-vector] " + (fmt % args), flush=True)

        def send_json(self, status: int, payload: dict):
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def authorized(self):
            scheme, _, token = self.headers.get("Authorization", "").partition(" ")
            return scheme.lower() == "bearer" and bool(api_key) and hmac.compare_digest(token, api_key)

        def do_GET(self):
            if self.path != "/healthz":
                self.send_json(404, {"ok": False, "error": "not found"})
                return
            try:
                self.send_json(200, {"ok": True, "service": "new-star-vector-search", "backend": "supabase-pgvector"})
            except Exception:
                self.send_json(503, {"ok": False, "error": "vector backend unavailable"})

        def do_POST(self):
            if self.path != "/search":
                self.send_json(404, {"ok": False, "error": "not found"})
                return
            if not self.authorized():
                self.send_json(401, {"ok": False, "error": "unauthorized"})
                return
            try:
                if self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower() != "application/json":
                    raise ValueError("Content-Type must be application/json")
                if self.headers.get("Transfer-Encoding"):
                    raise ValueError("transfer-encoded bodies are not supported")
                length = int(self.headers.get("Content-Length", "0"))
                if length < 1 or length > 16384:
                    raise ValueError("request body must be between 1 and 16384 bytes")
                payload = json.loads(self.rfile.read(length).decode("utf-8"))
                query = payload.get("query") if isinstance(payload, dict) else None
                if not isinstance(query, str) or not query.strip() or len(query) > 500:
                    raise ValueError("query must contain 1 to 500 characters")
                limit = payload.get("limit", 5)
                if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 10:
                    raise ValueError("limit must be an integer from 1 to 10")
                results = store.search(query, limit, payload.get("source"))
                self.send_json(200, {"ok": True, "retrieval": "vector-cosine", "results": results})
            except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
                self.send_json(400, {"ok": False, "error": str(exc)})
            except Exception:
                self.send_json(503, {"ok": False, "error": "semantic search unavailable"})
    return Handler


def main():
    token = os.environ.get("NEW_STAR_SEARCH_API_KEY", "")
    if not token:
        raise SystemExit("NEW_STAR_SEARCH_API_KEY is required")
    store = PgVectorSearch()
    host = os.environ.get("NEW_STAR_VECTOR_HOST", "0.0.0.0")
    port = int(os.environ.get("NEW_STAR_VECTOR_PORT", "8082"))
    server = ThreadingHTTPServer((host, port), make_handler(store, token))
    print(f"New Star vector search listening on {host}:{port}", flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
