import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from vector_sync import open_source_database, sync


class FakeStore:
    def __init__(self):
        self.pages = []

    def upsert_page(self, url, title, body, source):
        if url.endswith("/fail"):
            raise RuntimeError("simulated indexing failure")
        self.pages.append((url, title, body, source))
        return 2


class VectorSyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.database = Path(self.temp.name) / "crawler index #1.sqlite3"
        with sqlite3.connect(self.database) as db:
            db.execute(
                """CREATE TABLE pages (
                    id INTEGER PRIMARY KEY,
                    url TEXT NOT NULL,
                    host TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    snippet TEXT NOT NULL,
                    updated_at REAL NOT NULL
                )"""
            )
            db.executemany(
                "INSERT INTO pages(url,host,title,body,snippet,updated_at) VALUES(?,?,?,?,?,?)",
                [
                    ("https://example.org/a", "example.org", "A", "Body A", "Body A", 1.0),
                    ("https://example.org/b", "example.org", "B", "Body B", "Body B", 2.0),
                    ("https://example.org/fail", "example.org", "Fail", "Body fail", "Body fail", 3.0),
                ],
            )

    def tearDown(self):
        self.temp.cleanup()

    def test_read_only_open_supports_spaces_and_uri_characters(self):
        db = open_source_database(str(self.database))
        try:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM pages").fetchone()[0], 3)
            with self.assertRaises(sqlite3.OperationalError):
                db.execute("DELETE FROM pages")
        finally:
            db.close()

    def test_sync_reads_pages_in_batches_and_counts_chunks_and_pages(self):
        store = FakeStore()
        result = sync(str(self.database), store, batch_size=1)
        self.assertEqual(result, {
            "pages_processed": 3,
            "chunks_indexed": 4,
            "pages_failed": 1,
        })
        self.assertEqual([page[0] for page in store.pages], [
            "https://example.org/a",
            "https://example.org/b",
        ])
        self.assertEqual(store.pages[0], (
            "https://example.org/a", "A", "Body A", "example.org"
        ))

    def test_sync_rejects_invalid_batch_size(self):
        with self.assertRaisesRegex(ValueError, "batch_size"):
            sync(str(self.database), FakeStore(), batch_size=0)

    def test_sync_rejects_missing_database(self):
        missing = self.database.parent / "missing.sqlite3"
        with self.assertRaisesRegex(FileNotFoundError, "does not exist"):
            sync(str(missing), FakeStore())

    def test_sync_does_not_modify_source_database(self):
        before = self.database.read_bytes()
        sync(str(self.database), FakeStore(), batch_size=2)
        self.assertEqual(self.database.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
