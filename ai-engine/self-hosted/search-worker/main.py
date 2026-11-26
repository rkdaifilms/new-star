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
from requests.adapters import HTTPAdapter
from urllib3.connection import HTTPConnection, HTTPSConnection
from urllib3.connectionpool import HTTPConnectionPool, HTTPSConnectionPool
from urllib3 import PoolManager
from bs4 import BeautifulSoup
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from typing import Optional

app = FastAPI(title="New Star Private Search Worker", docs_url=None, redoc_url=None)
API_KEY = os.getenv("NEW_STAR_SEARCH_API_KEY", "")
DB_PATH = os.getenv("NEW_STAR_SEARCH_DB", "/data/search.sqlite3")
MAX_BYTES = 2 * 1024 * 1024
MAX_CRAWL_BYTES = 10 * 1024 * 1024
MAX_CRAWL_PAGES = 5
MAX_LINKS_PER_PAGE = 100
TIMEOUT = 12
HEADERS = {"User-Agent": "NewStarResearchBot/1.0 (+private search index)"}
BLOCKED_SUFFIXES = (".local", ".internal", ".localhost")


def auth(value: Optional[str]):
    if len(API_KEY) < 32:
        raise HTTPException(503, "Private search API key is not configured")
    if value != "Bearer " + API_KEY:
        raise HTTPException(401, "Unauthorized")


class _PinnedAddressMixin:
    def __init__(self, *args, pinned_ips=(), **kwargs):
        self._pinned_ips = tuple(pinned_ips)
        super().__init__(*args, **kwargs)

    def _new_conn(self):
        if not self._pinned_ips:
            raise OSError("No validated public IP addresses are available")
        last_error = None
        for address in self._pinned_ips:
            try:
                return socket.create_connection(
                    (address, self.port),
                    timeout=self.timeout,
                    source_address=self.source_address,
                    socket_options=self.socket_options,
                )
            except OSError as error:
                last_error = error
        if last_error is not None:
            raise last_error
        raise OSError("Could not connect to a validated public IP address")


class _PinnedHTTPConnection(_PinnedAddressMixin, HTTPConnection):
    pass


class _PinnedHTTPSConnection(_PinnedAddressMixin, HTTPSConnection):
    pass


class _PinnedHTTPConnectionPool(HTTPConnectionPool):
    ConnectionCls = _PinnedHTTPConnection


class _PinnedHTTPSConnectionPool(HTTPSConnectionPool):
    ConnectionCls = _PinnedHTTPSConnection


class _PinnedPoolManager(PoolManager):
    def connection_from_context(self, request_context):
        # Keep the pin out of PoolKey fields while still passing it to the pool.
        key_context = dict(request_context)
        key_context.pop("pinned_ips", None)
        scheme = key_context["scheme"].lower()
        pool_key = self.key_fn_by_scheme[scheme](key_context)
        return self.connection_from_pool_key(pool_key, request_context=request_context)


class PinnedAddressAdapter(HTTPAdapter):
    """Pin TCP destinations while retaining hostname-based TLS verification."""

    def __init__(self, pinned_ips):
        self.pinned_ips = tuple(pinned_ips)
        super().__init__(max_retries=0)

    def init_poolmanager(self, connections, maxsize, block=False, **pool_kwargs):
        self.poolmanager = _PinnedPoolManager(
            num_pools=connections,
            headers=self.headers,
            block=block,
            pinned_ips=self.pinned_ips,
            **pool_kwargs,
        )
        classes = dict(self.poolmanager.pool_classes_by_scheme)
        classes["http"] = _PinnedHTTPConnectionPool
        classes["https"] = _PinnedHTTPSConnectionPool
        self.poolmanager.pool_classes_by_scheme = classes


def resolve_public_addresses(host: str, port: int) -> list[str]:
    answers = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    if not answers:
        raise ValueError("Hostname did not resolve")
    addresses = []
    for item in answers:
        address = ipaddress.ip_address(item[4][0].split("%")[0])
        if not address.is_global:
            raise ValueError("Hostname resolved to a non-public address")
        value = str(address)
        if value not in addresses:
            addresses.append(value)
    return addresses


def validate_url_and_resolve(raw: str) -> tuple[str, list[str]]:
    try:
        safe_url = raw.strip()
        parsed = urlparse(safe_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError()
        host = parsed.hostname.rstrip(".").lower()
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        if port not in {80, 443} or (parsed.scheme == "https" and port != 443) or (parsed.scheme == "http" and port != 80):
            raise ValueError()
        if host == "localhost" or host.endswith(BLOCKED_SUFFIXES):
            raise ValueError()
        return safe_url, resolve_public_addresses(host, port)
    except Exception:
        raise HTTPException(400, "Only public HTTP or HTTPS websites are allowed")


def validate_url(raw: str) -> str:
    return validate_url_and_resolve(raw)[0]


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
    safe_url, pinned_addresses = validate_url_and_resolve(url)
    session = requests.Session()
    session.trust_env = False
    parsed_url = urlparse(safe_url)
    session.mount(parsed_url.scheme + "://", PinnedAddressAdapter(pinned_addresses))
    response = session.get(safe_url, headers=HEADERS, timeout=TIMEOUT, stream=True, allow_redirects=False)
    connection = getattr(response.raw, "_connection", None) or getattr(response.raw, "connection", None)
    sock = getattr(connection, "sock", None)
    if sock is None:
        response.close()
        raise HTTPException(502, "Website network destination could not be verified")
    try:
        peer_ip = ipaddress.ip_address(sock.getpeername()[0].split("%")[0])
    except (ValueError, OSError, IndexError):
        response.close()
        raise HTTPException(502, "Website network destination could not be verified")
    if not peer_ip.is_global:
        response.close()
        raise HTTPException(403, "Website resolved to a non-public network destination")
    if 300 <= response.status_code < 400:
        response.close()
        raise HTTPException(422, "Website redirects are not followed; crawl the final public URL directly")
    if response.status_code >= 400:
        response.close()
        raise HTTPException(422, "Website returned HTTP " + str(response.status_code))
    declared_length = response.headers.get("content-length")
    if declared_length:
        try:
            if int(declared_length) > MAX_BYTES:
                response.close()
                raise HTTPException(413, "Website page exceeds the 2 MiB indexing limit")
        except ValueError:
            response.close()
            raise HTTPException(502, "Website returned an invalid content length")
    content_type = response.headers.get("content-type", "").lower()
    if "text/html" not in content_type and "text/plain" not in content_type:
        response.close()
        raise HTTPException(415, "Only HTML and plain-text pages can be indexed")
    data = bytearray()
    try:
        for chunk in response.iter_content(65536):
            if not chunk:
                continue
            data.extend(chunk)
            if len(data) > MAX_BYTES:
                raise HTTPException(413, "Website page exceeds the 2 MiB indexing limit")
    finally:
        response.close()
    if "text/plain" in content_type:
        title, text = safe_url, data.decode(response.encoding or "utf-8", errors="replace")
    else:
        soup = BeautifulSoup(data, "html.parser")
        links = [urljoin(safe_url, anchor.get("href", "")) for anchor in soup.find_all("a", href=True)]
        for node in soup(["script", "style", "noscript", "svg", "nav", "footer"]):
            node.decompose()
        title = soup.title.get_text(" ", strip=True) if soup.title else safe_url
        text = soup.get_text(" ", strip=True)
    if "text/plain" in content_type:
        links = []
    text = re.sub(r"\s+", " ", text).strip()[:100000]
    return title[:500], text, links[:MAX_LINKS_PER_PAGE], len(data)


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
    root_parsed = urlparse(url)
    root_host = root_parsed.hostname
    root_scheme = root_parsed.scheme
    root_port = root_parsed.port or (443 if root_scheme == "https" else 80)
    pending = [url]
    visited = set()
    indexed_pages = []
    total_characters = 0
    total_bytes = 0
    while pending and len(visited) < MAX_CRAWL_PAGES:
        current = validate_url(pending.pop(0))
        parsed = urlparse(current)
        current_port = parsed.port or (443 if parsed.scheme == "https" else 80)
        if parsed.hostname != root_host or parsed.scheme != root_scheme or current_port != root_port or current in visited:
            continue
        visited.add(current)
        try:
            title, text, links, page_bytes = fetch_page(current)
        except HTTPException:
            if current == url:
                raise
            continue
        if total_bytes + page_bytes > MAX_CRAWL_BYTES:
            if current == url and not indexed_pages:
                raise HTTPException(413, "Crawl exceeds the 10 MiB total indexing limit")
            break
        if len(text) < 40:
            if current == url:
                raise HTTPException(422, "Website did not contain enough readable text")
            continue
        with database() as db:
            existing = db.execute("SELECT id FROM pages WHERE url=?", (current,)).fetchone()
            page_id = existing[0] if existing else str(uuid.uuid4())
            db.execute("DELETE FROM pages_fts WHERE url=?", (current,))
            db.execute("INSERT INTO pages(id,url,title,text,fetched_at) VALUES(?,?,?,?,?) ON CONFLICT(url) DO UPDATE SET title=excluded.title,text=excluded.text,fetched_at=excluded.fetched_at", (page_id,current,title,text,int(time.time())))
            db.execute("INSERT INTO pages_fts(title,text,url) VALUES(?,?,?)", (title,text,current))
        indexed_pages.append({"page_id": page_id, "url": current, "title": title, "characters_indexed": len(text)})
        total_characters += len(text)
        total_bytes += page_bytes
        for link in links:
            candidate = urldefrag(link)[0]
            if len(candidate) > 2048:
                continue
            link_parsed = urlparse(candidate)
            link_port = link_parsed.port or (443 if link_parsed.scheme == "https" else 80)
            if link_parsed.scheme == root_scheme and link_parsed.hostname == root_host and link_port == root_port and candidate not in visited and candidate not in pending:
                pending.append(candidate)
    root = indexed_pages[0]
    return {"ok": True, "page_id": root["page_id"], "url": url, "title": root["title"], "characters_indexed": total_characters, "pages_indexed": len(indexed_pages), "pages": indexed_pages}


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
