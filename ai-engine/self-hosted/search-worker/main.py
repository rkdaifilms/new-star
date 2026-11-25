import ipaddress
import os
import re
import socket
import sqlite3
import time
import uuid
from contextlib import contextmanager
from urllib.parse import urljoin, urlparse, urldefrag

import requests
from bs4 import BeautifulSoup
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from typing import Optional

app = FastAPI(title="New Star Private Search Worker", docs_url=None, redoc_url=None)
API_KEY = os.getenv("NEW_STAR_SEARCH_API_KEY", "")
DB_PATH = os.getenv("NEW_STAR_SEARCH_DB", "/data/search.sqlite3")
MAX_BYTES = 2 * 1024 * 1024
TIMEOUT = 12
HEADERS = {"User-Agent": "NewStarResearchBot/1.0 (+private search index)"}
BLOCKED_SUFFIXES = (".local", ".internal", ".localhost")


def auth(value: Optional[str]):
    if len(API_KEY) < 32:
        raise HTTPException(503, "Private search API key is not configured")
    if value != "Bearer " + API_KEY:
        raise HTTPException(401, "Unauthorized")


def validate_url(raw: str) -> str:
    try:
        parsed = urlparse(raw.strip())
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError()
        host = parsed.hostname.rstrip(".").lower()
        if host == "localhost" or host.endswith(BLOCKED_SUFFIXES):
            raise ValueError()
        addresses = socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80), type=socket.SOCK_STREAM)
        if not addresses:
            raise ValueError()
        for item in addresses:
            address = ipaddress.ip_address(item[4][0].split("%")[0])
            if not address.is_global:
                raise ValueError()
        return raw.strip()
    except Exception:
        raise HTTPException(400, "Only public HTTP or HTTPS websites are allowed")


@contextmanager
def database():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=10)
    try:
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("CREATE TABLE IF NOT EXISTS pages (id TEXT PRIMARY KEY, url TEXT UNIQUE NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, fetched_at INTEGER NOT NULL)")
        connection.execute("CREATE VIRTUAL TABLE IF NOT EXISTS pages_fts USING fts5(title, text, url UNINDEXED)")
        yield connection
        connection.commit()
    finally:
        connection.close()


def fetch_page(url: str):
    safe_url = validate_url(url)
    session = requests.Session()
    session.trust_env = False
    response = session.get(safe_url, headers=HEADERS, timeout=TIMEOUT, stream=True, allow_redirects=False)
    if response.status_code in {301, 302, 303, 307, 308}:
        response.close()
        raise HTTPException(422, "Website redirects are not followed; crawl the final public URL directly")
    if response.status_code >= 400:
        raise HTTPException(422, "Website returned HTTP " + str(response.status_code))
    content_type = response.headers.get("content-type", "").lower()
    if "text/html" not in content_type and "text/plain" not in content_type:
        response.close()
        raise HTTPException(415, "Only HTML and plain-text pages can be indexed")
    data = bytearray()
    for chunk in response.iter_content(65536):
        data.extend(chunk)
        if len(data) > MAX_BYTES:
            response.close()
            raise HTTPException(413, "Website page exceeds the 2 MiB indexing limit")
    response.close()
    if "text/plain" in content_type:
        title, text = safe_url, data.decode(response.encoding or "utf-8", errors="replace")
    else:
        soup = BeautifulSoup(data, "html.parser")
        for node in soup(["script", "style", "noscript", "svg", "nav", "footer"]):
            node.decompose()
        title = soup.title.get_text(" ", strip=True) if soup.title else safe_url
        text = soup.get_text(" ", strip=True)
    text = re.sub(r"\s+", " ", text).strip()[:100000]
    return title[:500], text


class CrawlRequest(BaseModel):
    url: str = Field(min_length=8, max_length=2048)


class SearchRequest(BaseModel):
    query: str = Field(min_length=2, max_length=500)
    limit: int = Field(default=5, ge=1, le=10)


@app.get("/health")
def health():
    with database() as db:
        count = db.execute("SELECT count(*) FROM pages").fetchone()[0]
    return {"ok": True, "service": "new-star-private-search", "indexed_pages": count}


@app.post("/v1/crawl")
def crawl(request: CrawlRequest, authorization: Optional[str] = Header(default=None)):
    auth(authorization)
    url = validate_url(request.url)
    title, text = fetch_page(url)
    if len(text) < 40:
        raise HTTPException(422, "Website did not contain enough readable text")
    with database() as db:
        existing = db.execute("SELECT id FROM pages WHERE url=?", (url,)).fetchone()
        page_id = existing[0] if existing else str(uuid.uuid4())
        db.execute("DELETE FROM pages_fts WHERE url=?", (url,))
        db.execute("INSERT INTO pages(id,url,title,text,fetched_at) VALUES(?,?,?,?,?) ON CONFLICT(url) DO UPDATE SET title=excluded.title,text=excluded.text,fetched_at=excluded.fetched_at", (page_id,url,title,text,int(time.time())))
        db.execute("INSERT INTO pages_fts(title,text,url) VALUES(?,?,?)", (title,text,url))
    return {"ok": True, "page_id": page_id, "url": url, "title": title, "characters_indexed": len(text)}


@app.post("/v1/search")
def search(request: SearchRequest, authorization: Optional[str] = Header(default=None)):
    auth(authorization)
    terms = re.findall(r"[\w]{2,}", request.query.lower(), flags=re.UNICODE)
    if not terms:
        raise HTTPException(400, "Search query must contain searchable words")
    match = " AND ".join('"' + term.replace('"', '""') + '"' for term in terms[:12])
    with database() as db:
        try:
            rows = db.execute("SELECT title,url,snippet(pages_fts,1,'','', ' … ',18) AS excerpt,fetched_at FROM pages_fts WHERE pages_fts MATCH ? ORDER BY rank LIMIT ?", (match,request.limit)).fetchall()
        except sqlite3.OperationalError:
            rows = []
    return {"ok": True, "query": request.query, "results": [{"title":r[0],"url":r[1],"snippet":r[2],"fetchedAt":r[3],"source":"new_star_private_index"} for r in rows], "indexed": True}


@app.post("/v1/crawl/batch")
def crawl_batch(urls: list[str], authorization: Optional[str] = Header(default=None)):
    auth(authorization)
    if not urls or len(urls) > 10:
        raise HTTPException(400, "Provide between 1 and 10 URLs")
    results = []
    for url in urls:
        try:
            request = CrawlRequest(url=url)
            results.append(crawl(request, authorization))
        except HTTPException as error:
            results.append({"ok": False, "url": url[:2048], "error": str(error.detail)})
    return {"ok": True, "results": results}
