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
        if not chunks:
            return 0
        records = [(url, title[:240], source[:253], part, self.embedder.encode(part, passage=True)) for part in chunks]
        with self.connect() as connection:
            with connection.cursor() as cursor:
                cursor.execute("DELETE FROM public.new_star_search_chunks WHERE url=%s", (url,))
                cursor.executemany(
                    """INSERT INTO public.new_star_search_chunks(url,title,source,content,embedding)
                       VALUES (%s,%s,%s,%s,%s)""", records)
        return len(records)

    def search(self, query: str, limit: int = 5, source: str | None = None) -> list[dict[str, Any]]:
        vector = self.embedder.encode(query, passage=False)
        bounded = min(10, max(1, int(limit)))
        with self.connect() as connection:
            with connection.cursor() as cursor:
                if source:
                    cursor.execute(
                        """SELECT title,url,source,content,1-(embedding <=> %s) AS similarity
                           FROM public.new_star_search_chunks WHERE source=%s
                           ORDER BY embedding <=> %s LIMIT %s""",
                        (vector, source, vector, bounded))
                else:
                    cursor.execute(
                        """SELECT title,url,source,content,1-(embedding <=> %s) AS similarity
                           FROM public.new_star_search_chunks ORDER BY embedding <=> %s LIMIT %s""",
                        (vector, vector, bounded))
                rows = cursor.fetchall()
        return [{"title": row[0], "url": row[1], "source": row[2],
                 "snippet": row[3][:420], "similarity": round(float(row[4]), 6)} for row in rows]
