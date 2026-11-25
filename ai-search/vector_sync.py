"""Backfill the existing SQLite crawler index into Supabase pgvector."""
from __future__ import annotations

import argparse
import json
import os
import sqlite3
from pathlib import Path

from vector_backend import PgVectorSearch


def open_source_database(sqlite_path: str) -> sqlite3.Connection:
    """Open the crawler database read-only, including paths with URI characters."""
    path = Path(sqlite_path).expanduser().resolve()
    if not path.is_file():
        raise FileNotFoundError(f"crawler database does not exist: {path}")
    uri = path.as_uri() + "?mode=ro"
    db = sqlite3.connect(uri, uri=True)
    db.row_factory = sqlite3.Row
    return db


def sync(sqlite_path: str, store: PgVectorSearch, batch_size: int = 25) -> dict[str, int]:
    if not 1 <= batch_size <= 250:
        raise ValueError("batch_size must be between 1 and 250")

    db = open_source_database(sqlite_path)
    indexed = failed = processed = 0
    try:
        columns = {row["name"] for row in db.execute("PRAGMA table_info(pages)")}
        required = {"url", "host", "title", "body"}
        missing = sorted(required - columns)
        if missing:
            raise RuntimeError("crawler pages table is missing required columns: " + ", ".join(missing))

        cursor = db.execute("SELECT url,host,title,body FROM pages ORDER BY id")
        while True:
            rows = cursor.fetchmany(batch_size)
            if not rows:
                break
            for row in rows:
                processed += 1
                try:
                    url = row["url"]
                    host = row["host"]
                    title = row["title"] or ""
                    body = row["body"] or ""
                    if not isinstance(url, str) or not url.strip():
                        raise ValueError("page URL is empty or invalid")
                    if not isinstance(host, str):
                        raise ValueError("page host is invalid")
                    if not isinstance(title, str) or not isinstance(body, str):
                        raise ValueError("page title or body is invalid")
                    indexed += store.upsert_page(url, title, body, host)
                except Exception:
                    failed += 1
    finally:
        db.close()
    return {
        "pages_processed": processed,
        "chunks_indexed": indexed,
        "pages_failed": failed,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--sqlite-db",
        default=os.environ.get("NEW_STAR_SEARCH_DB", "/var/lib/new-star-search/index.sqlite3"),
    )
    parser.add_argument("--batch-size", type=int, default=25)
    parser.add_argument(
        "--fail-on-errors",
        action="store_true",
        help="exit with status 1 if one or more pages could not be indexed",
    )
    args = parser.parse_args()
    try:
        result = sync(args.sqlite_db, PgVectorSearch(), args.batch_size)
    except (OSError, sqlite3.Error, RuntimeError, ValueError) as exc:
        parser.exit(1, f"vector sync failed: {exc}\n")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    if args.fail_on_errors and result["pages_failed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
