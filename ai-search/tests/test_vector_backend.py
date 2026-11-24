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

    def test_database_url_required(self):
        with patch.dict("os.environ", {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "NEW_STAR_VECTOR_DATABASE_URL"):
                PgVectorSearch(database_url="", embedder=FakeEmbedder())


if __name__ == "__main__":
    unittest.main()
