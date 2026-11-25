import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from vector_backend import MultilingualEmbedder, PgVectorSearch


class FakeEmbedder:
    def encode(self, text, passage=False):
        return [0.0] * 384


class VectorBackendTests(unittest.TestCase):
    def test_chunking_preserves_text_and_bounds_chunks(self):
        text = "नेपाली भाषा " * 500
        chunks = PgVectorSearch.chunks(text, size=200, overlap=20)
        self.assertGreater(len(chunks), 1)
        self.assertTrue(all(len(chunk) <= 200 for chunk in chunks))
        self.assertTrue(all(chunk.strip() for chunk in chunks))

    def test_empty_text_has_no_chunks(self):
        self.assertEqual(PgVectorSearch.chunks("   "), [])
        self.assertEqual(PgVectorSearch.chunks("\\n"), [])

    def test_empty_page_deletes_stale_vectors_without_inserting(self):
        class FakeCursor:
            def __init__(self):
                self.executed = []
                self.inserted = []
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False
            def execute(self, query, params):
                self.executed.append((query, params))
            def executemany(self, query, records):
                self.inserted.extend(records)

        class FakeConnection:
            def __init__(self):
                self.cursor_instance = FakeCursor()
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return False
            def cursor(self):
                return self.cursor_instance

        search = object.__new__(PgVectorSearch)
        connection = FakeConnection()
        with patch.object(search, "connect", return_value=connection):
            self.assertEqual(search.upsert_page("https://example.org/empty", "", "", "example.org"), 0)

        self.assertEqual(len(connection.cursor_instance.executed), 1)
        self.assertIn("DELETE FROM public.new_star_search_chunks",
                      connection.cursor_instance.executed[0][0])
        self.assertEqual(connection.cursor_instance.inserted, [])

    def test_embedding_dimension_is_enforced(self):
        class WrongModel:
            def embed(self, values):
                yield [0.1, 0.2]
        encoder = object.__new__(MultilingualEmbedder)
        encoder.model = WrongModel()
        with self.assertRaisesRegex(RuntimeError, "dimension mismatch"):
            encoder.encode("नमस्ते")

    def test_reranker_uses_semantic_and_unicode_lexical_signals(self):
        candidates = [
            {"title": "Unrelated page", "snippet": "general information", "similarity": 0.95},
            {"title": "नेपाली भाषा खोज", "snippet": "भाषा र खोज प्रणाली", "similarity": 0.72},
        ]
        ranked = PgVectorSearch.rerank("नेपाली भाषा", candidates, 1)
        self.assertEqual(ranked[0]["title"], "नेपाली भाषा खोज")
        self.assertIn("rerank_score", ranked[0])

    def test_reranker_tokenizes_ascii_words(self):
        candidates = [
            {"title": "Unrelated page", "snippet": "weather forecast", "similarity": 0.90},
            {"title": "vector search guide", "snippet": "semantic vector retrieval", "similarity": 0.72},
        ]
        ranked = PgVectorSearch.rerank("vector search", candidates, 2)
        self.assertEqual(ranked[0]["title"], "vector search guide")

    def test_reranker_handles_unicode_word_boundaries(self):
        candidates = [
            {"title": "असंबंधित पृष्ठ", "snippet": "सामान्य जानकारी", "similarity": 0.90},
            {"title": "नेपाली भाषा खोज", "snippet": "भाषा र खोज प्रणाली", "similarity": 0.72},
        ]
        ranked = PgVectorSearch.rerank("नेपाली भाषा", candidates, 2)
        self.assertEqual(ranked[0]["title"], "नेपाली भाषा खोज")

    def test_reciprocal_rank_fusion_combines_vector_and_lexical_results(self):
        vector = [
            {"id": 1, "title": "semantic result", "url": "https://a.example", "source": "a", "snippet": "meaning", "similarity": 0.8},
            {"id": 2, "title": "vector only", "url": "https://b.example", "source": "b", "snippet": "nearby", "similarity": 0.7},
        ]
        lexical = [
            {"id": 2, "title": "exact keyword result", "url": "https://b.example", "source": "b", "snippet": "exact", "similarity": 0.0},
            {"id": 3, "title": "lexical only", "url": "https://c.example", "source": "c", "snippet": "word match", "similarity": 0.0},
        ]
        fused = PgVectorSearch.fuse_rankings(vector, lexical, 10)
        self.assertEqual({item["id"] for item in fused}, {1, 2, 3})
        self.assertEqual(fused[0]["id"], 2)
        self.assertIn("vector_rank", fused[0])
        self.assertIn("lexical_rank", fused[0])

    def test_hybrid_reranker_returns_bounded_scores(self):
        candidates = [
            {"id": 1, "title": "exact match", "snippet": "नेपाली भाषा", "similarity": 0.5, "rrf_score": 0.03},
            {"id": 2, "title": "other", "snippet": "unrelated", "similarity": 0.7, "rrf_score": 0.01},
        ]
        ranked = PgVectorSearch.rerank("नेपाली भाषा", candidates, 1)
        self.assertEqual(len(ranked), 1)
        self.assertGreaterEqual(ranked[0]["rerank_score"], 0.0)
        self.assertLessEqual(ranked[0]["rerank_score"], 1.0)

    def test_database_url_required(self):
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "NEW_STAR_VECTOR_DATABASE_URL"):
                PgVectorSearch(database_url="", embedder=FakeEmbedder())


if __name__ == "__main__":
    unittest.main()
