"""Backfill the existing SQLite crawler index into Supabase pgvector."""
from __future__ import annotations

import argparse
import os
import sqlite3

from vector_backend import PgVectorSearch


def sync(sqlite_path: str, store: PgVectorSearch, batch_size: int = 25) -> dict[str, int]:
    if not 1 <= batch_size <= 250:
        raise ValueError("batch_size must be between 1 and 250")
    db = sqlite3.connect(f"file:{sqlite_path}?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    indexed = failed = 0
    try:
        cursor = db.execute("SELECT url,host,title,body FROM pages ORDER BY id")
        while True:
            rows = cursor.fetchmany(batch_size)
            if not rows:
                break
            for row in rows:
                try:
                    indexed += store.upsert_page(row["url"], row["title"], row["body"], row["host"])
                except Exception:
                    failed += 1
    finally:
        db.close()
    return {"chunks_indexed": indexed, "pages_failed": failed}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sqlite-db", default=os.environ.get("NEW_STAR_SEARCH_DB", "/var/lib/new-star-search/index.sqlite3"))
    parser.add_argument("--batch-size", type=int, default=25)
    args = parser.parse_args()
    result = sync(args.sqlite_db, PgVectorSearch(), args.batch_size)
    print(result)


if __name__ == "__main__":
    main()
