"""Optional multilingual semantic retrieval backend backed by Supabase/Postgres pgvector.

Uses a local FastEmbed ONNX encoder. The encoder is a replaceable baseline, not a New Star-trained model.
"""
from __future__ import annotations

import os
import re
from typing import Any

MODEL_NAME = os.environ.get("NEW_STAR_EMBEDDING_MODEL", "intfloat/multilingual-e5-small")
DIMENSIONS = 384


class MultilingualEmbedder:
    def __init__(self, model_name: str = MODEL_NAME):
        try:
            from fastembed import TextEmbedding
        except ImportError as exc:
            raise RuntimeError("Install ai-search/requirements.txt to enable vector retrieval") from exc
        self.model = TextEmbedding(model_name=model_name)

    def encode(self, text: str, passage: bool = False) -> list[float]:
        normalized = re.sub(r"\s+", " ", text).strip()
        if not normalized:
            raise ValueError("embedding input cannot be empty")
        prefix = "passage: " if passage else "query: "
        vector = next(self.model.embed([prefix + normalized[:8000]]))
        values = [float(value) for value in vector]
        if len(values) != DIMENSIONS:
            raise RuntimeError(f"embedding dimension mismatch: expected {DIMENSIONS}, got {len(values)}")
        return values


class PgVectorSearch:
    def __init__(self, database_url: str | None = None, embedder: Any | None = None):
        self.database_url = database_url or os.environ.get("NEW_STAR_VECTOR_DATABASE_URL", "")
        if not self.database_url:
            raise RuntimeError("NEW_STAR_VECTOR_DATABASE_URL is required for pgvector retrieval")
        try:
            import psycopg
            from pgvector.psycopg import register_vector
        except ImportError as exc:
            raise RuntimeError("Install psycopg[binary] and pgvector to enable vector retrieval") from exc
        self.psycopg = psycopg
        self.register_vector = register_vector
        self.embedder = embedder or MultilingualEmbedder()
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='new_star_search_chunks'")
                if cursor.fetchone() is None:
                    raise RuntimeError("Apply the New Star pgvector migration before enabling vector retrieval")

    def connect(self):
        connection = self.psycopg.connect(self.database_url, connect_timeout=8)
        self.register_vector(connection, schema="extensions")
        return connection

    def health(self) -> bool:
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT 1")
                return cursor.fetchone()[0] == 1

    @staticmethod
    def chunks(text: str, size: int = 1100, overlap: int = 140) -> list[str]:
        clean = re.sub(r"\s+", " ", text).strip()
        if not clean:
            return []
        output = []
        start = 0
        while start < len(clean):
            end = min(len(clean), start + size)
            if end < len(clean):
                boundary = clean.rfind(" ", start + size // 2, end)
                if boundary > start:
                    end = boundary
            output.append(clean[start:end])
            if end == len(clean):
                break
            start = max(start + 1, end - overlap)
        return output

    def upsert_page(self, url: str, title: str, body: str, source: str) -> int:
        chunks = self.chunks((title + "\n" + body)[:50000])
        records = [(url, title[:240], source[:253], part, self.embedder.encode(part, passage=True)) for part in chunks]
        # Delete and reinsert in one transaction. If a crawled page becomes empty,
        # remove its old chunks instead of leaving stale content searchable.
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("DELETE FROM public.new_star_search_chunks WHERE url=%s", (url,))
                if records:
                    cursor.executemany(
                        """INSERT INTO public.new_star_search_chunks(url,title,source,content,embedding)
                           VALUES (%s,%s,%s,%s,%s)""", records)
        return len(records)

    @staticmethod
    def fuse_rankings(vector_rows: list[dict[str, Any]], lexical_rows: list[dict[str, Any]],
                      limit: int, rank_constant: int = 60) -> list[dict[str, Any]]:
        """Fuse vector and full-text rankings with reciprocal-rank fusion."""
        fused: dict[int, dict[str, Any]] = {}
        for ranking_name, rows in (("vector", vector_rows), ("lexical", lexical_rows)):
            for rank, item in enumerate(rows, start=1):
                key = int(item["id"])
                entry = fused.setdefault(key, {**item, "rrf_score": 0.0})
                entry["rrf_score"] += 1.0 / (rank_constant + rank)
                entry[f"{ranking_name}_rank"] = rank
                if ranking_name == "lexical":
                    entry["title"] = item["title"]
                    entry["snippet"] = item["snippet"]
                    entry["source"] = item["source"]
                    entry["url"] = item["url"]
        return sorted(fused.values(), key=lambda item: item["rrf_score"], reverse=True)[:limit]

    @staticmethod
    def rerank(query: str, candidates: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
        """Blend cosine relevance, RRF and Unicode token overlap."""
        query_terms = set(re.findall(r"[\w]+", query.casefold(), flags=re.UNICODE))
        rescored = []
        for item in candidates:
            text_terms = set(re.findall(
                r"[\w]+", (str(item.get("title", "")) + " " + str(item.get("snippet", ""))).casefold(),
                flags=re.UNICODE))
            overlap = len(query_terms & text_terms) / max(1, len(query_terms))
            semantic = (max(-1.0, min(1.0, float(item.get("similarity", 0.0)))) + 1.0) / 2.0
            rrf = min(1.0, float(item.get("rrf_score", 0.0)) * 30.0)
            score = 0.45 * semantic + 0.40 * overlap + 0.15 * rrf
            rescored.append({**item, "rerank_score": round(score, 6)})
        rescored.sort(key=lambda item: (item["rerank_score"], item.get("similarity", 0.0)), reverse=True)
        return rescored[:limit]

    def search(self, query: str, limit: int = 5, source: str | None = None) -> list[dict[str, Any]]:
        if not isinstance(query, str) or not query.strip():
            raise ValueError("query cannot be empty")
        vector = self.embedder.encode(query, passage=False)
        bounded = min(10, max(1, int(limit)))
        candidate_limit = min(50, bounded * 4)
        source_filter = " AND source=%s" if source else ""
        with self.connect() as connection:
            with connection.cursor() as cursor:
                vector_sql = (
                    "SELECT id,title,url,source,content,1-(embedding <=> %s) AS similarity "
                    "FROM public.new_star_search_chunks WHERE TRUE" + source_filter +
                    " ORDER BY embedding <=> %s LIMIT %s"
                )
                vector_params = [vector] + ([source] if source else []) + [vector, candidate_limit]
                cursor.execute(vector_sql, vector_params)
                vector_rows = cursor.fetchall()
                lexical_sql = (
                    "SELECT id,title,url,source,content,0.0 AS similarity "
                    "FROM public.new_star_search_chunks "
                    "WHERE search_vector @@ plainto_tsquery('simple', %s)" + source_filter +
                    " ORDER BY ts_rank_cd(search_vector, plainto_tsquery('simple', %s)) DESC LIMIT %s"
                )
                lexical_params = [query] + ([source] if source else []) + [query, candidate_limit]
                cursor.execute(lexical_sql, lexical_params)
                lexical_rows = cursor.fetchall()
        def as_item(row):
            return {"id": int(row[0]), "title": row[1], "url": row[2], "source": row[3],
                    "snippet": row[4][:420], "similarity": float(row[5])}
        fused = self.fuse_rankings(
            [as_item(row) for row in vector_rows],
            [as_item(row) for row in lexical_rows],
            candidate_limit,
        )
        return self.rerank(query, fused, bounded)
