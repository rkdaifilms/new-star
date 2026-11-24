"""New Star Search: allowlisted HTTPS crawler, SQLite FTS index, and authenticated API."""
from __future__ import annotations

import argparse
import ipaddress
import json
import os
import re
import socket
import sqlite3
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from html.parser import HTMLParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.robotparser import RobotFileParser

USER_AGENT = os.environ.get("NEW_STAR_SEARCH_USER_AGENT", "NewStarSearchBot/1.0")
MAX_PAGE_BYTES = max(65536, int(os.environ.get("NEW_STAR_SEARCH_MAX_PAGE_BYTES", "2000000")))
FETCH_TIMEOUT = max(2, int(os.environ.get("NEW_STAR_SEARCH_FETCH_TIMEOUT", "12")))


def allowed_domains() -> tuple[str, ...]:
    raw = os.environ.get("NEW_STAR_SEARCH_ALLOWED_DOMAINS", "")
    return tuple(sorted({item.strip().lower().lstrip(".") for item in re.split(r"[,;]", raw) if item.strip()}))


def normalize_url(value: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("only credential-free HTTPS URLs are allowed")
    if parsed.port not in (None, 443):
        raise ValueError("non-standard HTTPS ports are not allowed")
    host = parsed.hostname.encode("idna").decode("ascii").lower()
    path = parsed.path or "/"
    return urllib.parse.urlunsplit(("https", host, path, parsed.query, ""))


def host_is_allowed(host: str, domains: tuple[str, ...]) -> bool:
    normalized = host.lower().rstrip(".")
    return any(normalized == domain or normalized.endswith("." + domain) for domain in domains)


def resolve_public_host(host: str) -> None:
    try:
        records = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    except OSError as exc:
        raise ValueError("host DNS resolution failed") from exc
    if not records:
        raise ValueError("host has no address records")
    for record in records:
        address = ipaddress.ip_address(record[4][0].split("%", 1)[0])
        if not address.is_global:
            raise ValueError("private, local, or reserved network destinations are blocked")


class PageParser(HTMLParser):
    def __init__(self, base_url: str):
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.title_parts: list[str] = []
        self.text_parts: list[str] = []
        self.links: list[str] = []
        self.in_title = False
        self.skip_depth = 0

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if tag == "title":
            self.in_title = True
        if tag in ("script", "style", "noscript", "svg"):
            self.skip_depth += 1
        if tag == "a" and isinstance(values.get("href"), str):
            try:
                target = urllib.parse.urljoin(self.base_url, values["href"])
                self.links.append(normalize_url(target))
            except ValueError:
                pass

    def handle_endtag(self, tag):
        if tag == "title":
            self.in_title = False
        if tag in ("script", "style", "noscript", "svg") and self.skip_depth:
            self.skip_depth -= 1

    def handle_data(self, data):
        cleaned = " ".join(data.split())
        if not cleaned:
            return
        if self.in_title:
            self.title_parts.append(cleaned)
        if not self.skip_depth:
            self.text_parts.append(cleaned)

    def parsed(self):
        title = " ".join(self.title_parts).strip()[:240] or urllib.parse.urlsplit(self.base_url).hostname or "Untitled page"
        body = " ".join(self.text_parts)
        body = re.sub(r"\s+", " ", body).strip()
        return title, body, self.links


class SearchIndex:
    def __init__(self, database=None):
        self.database = str(database or os.environ.get("NEW_STAR_SEARCH_DB", "var/new-star-search.sqlite3"))
        Path(self.database).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS pages (
                id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, host TEXT NOT NULL,
                title TEXT NOT NULL, body TEXT NOT NULL, snippet TEXT NOT NULL,
                updated_at REAL NOT NULL)""")
            db.execute("CREATE VIRTUAL TABLE IF NOT EXISTS pages_fts USING fts5(title,body,content='pages',content_rowid='id',tokenize='unicode61')")
            db.execute("""CREATE TRIGGER IF NOT EXISTS pages_ai AFTER INSERT ON pages BEGIN
                INSERT INTO pages_fts(rowid,title,body) VALUES(new.id,new.title,new.body); END""")
            db.execute("""CREATE TRIGGER IF NOT EXISTS pages_ad AFTER DELETE ON pages BEGIN
                INSERT INTO pages_fts(pages_fts,rowid,title,body) VALUES('delete',old.id,old.title,old.body); END""")
            db.execute("""CREATE TRIGGER IF NOT EXISTS pages_au AFTER UPDATE ON pages BEGIN
                INSERT INTO pages_fts(pages_fts,rowid,title,body) VALUES('delete',old.id,old.title,old.body);
                INSERT INTO pages_fts(rowid,title,body) VALUES(new.id,new.title,new.body); END""")

    def connect(self):
        db = sqlite3.connect(self.database, timeout=30)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=30000")
        return db

    def upsert(self, url: str, title: str, body: str) -> None:
        canonical = normalize_url(url)
        host = urllib.parse.urlsplit(canonical).hostname or ""
        cleaned_title = " ".join(title.split())[:240] or host
        cleaned_body = " ".join(body.split())[:200000]
        snippet = cleaned_body[:420]
        with self.connect() as db:
            db.execute("""INSERT INTO pages(url,host,title,body,snippet,updated_at)
                VALUES(?,?,?,?,?,?) ON CONFLICT(url) DO UPDATE SET
                host=excluded.host,title=excluded.title,body=excluded.body,
                snippet=excluded.snippet,updated_at=excluded.updated_at""",
                (canonical,host,cleaned_title,cleaned_body,snippet,time.time()))

    def search(self, query: str, limit: int = 5) -> list[dict[str, str]]:
        words = re.findall(r"[\w]+", query, flags=re.UNICODE)[:12]
        if not words:
            return []
        safe_query = " OR ".join('"' + word.replace('"', '""') + '"' for word in words)
        bounded_limit = min(10, max(1, int(limit)))
        with self.connect() as db:
            rows = db.execute("""SELECT p.title,p.url,p.host,p.snippet
                FROM pages_fts f JOIN pages p ON p.id=f.rowid
                WHERE pages_fts MATCH ? ORDER BY bm25(pages_fts),p.updated_at DESC LIMIT ?""",
                (safe_query,bounded_limit)).fetchall()
        return [{"title":row["title"],"url":row["url"],"snippet":row["snippet"],"source":row["host"]} for row in rows]

    def page_count(self) -> int:
        with self.connect() as db:
            return int(db.execute("SELECT COUNT(*) FROM pages").fetchone()[0])


class SafeCrawler:
    def __init__(self, index: SearchIndex, domains: tuple[str, ...], max_pages=100, delay_seconds=1.0):
        if not domains:
            raise ValueError("NEW_STAR_SEARCH_ALLOWED_DOMAINS must list at least one domain")
        self.index = index
        self.domains = domains
        self.max_pages = max(1, min(1000, int(max_pages)))
        self.delay_seconds = max(0.5, float(delay_seconds))
        self.robots: dict[str, RobotFileParser] = {}
        self.visited: set[str] = set()
        self.opener = urllib.request.build_opener(_NoRedirect())

    def _check_url(self, value: str) -> str:
        url = normalize_url(value)
        host = urllib.parse.urlsplit(url).hostname or ""
        if not host_is_allowed(host, self.domains):
            raise ValueError("URL host is outside the configured crawl allowlist")
        resolve_public_host(host)
        return url

    def _read(self, url: str, accepted_types=("text/html",)) -> tuple[bytes, str]:
        current = self._check_url(url)
        for _ in range(4):
            request = urllib.request.Request(current, headers={"User-Agent": USER_AGENT, "Accept": "text/html"})
            try:
                with self.opener.open(request, timeout=FETCH_TIMEOUT) as response:
                    content_type = response.headers.get_content_type()
                    if content_type not in accepted_types:
                        raise ValueError("crawler received an unsupported content type")
                    data = response.read(MAX_PAGE_BYTES + 1)
                    if len(data) > MAX_PAGE_BYTES:
                        raise ValueError("page exceeds the configured download size limit")
                    charset = response.headers.get_content_charset() or "utf-8"
                    return data, charset
            except urllib.error.HTTPError as exc:
                if exc.code not in (301,302,303,307,308):
                    raise
                location = exc.headers.get("Location")
                if not location:
                    raise ValueError("redirect response has no Location header") from exc
                current = self._check_url(urllib.parse.urljoin(current, location))
        raise ValueError("too many redirects")

    def _robots_allowed(self, url: str) -> bool:
        parsed = urllib.parse.urlsplit(url)
        origin = "https://" + parsed.netloc
        parser = self.robots.get(origin)
        if parser is None:
            parser = RobotFileParser()
            robots_url = origin + "/robots.txt"
            try:
                data, charset = self._read(robots_url, ("text/plain", "text/html"))
                parser.parse(data.decode(charset, errors="replace").splitlines())
            except urllib.error.HTTPError as exc:
                if exc.code == 404:
                    parser.parse(["User-agent: *", "Allow: /"])
                else:
                    parser.parse(["User-agent: *", "Disallow: /"])
            except Exception:
                parser.parse(["User-agent: *", "Disallow: /"])
            self.robots[origin] = parser
        return parser.can_fetch(USER_AGENT, url)

    def crawl(self, seeds: list[str]) -> dict[str, int]:
        queue = []
        for seed in seeds:
            queue.append((self._check_url(seed), 0))
        indexed = failed = 0
        while queue and len(self.visited) < self.max_pages:
            url, depth = queue.pop(0)
            if url in self.visited:
                continue
            self.visited.add(url)
            if not self._robots_allowed(url):
                continue
            try:
                data, charset = self._read(url)
                parser = PageParser(url)
                parser.feed(data.decode(charset, errors="replace"))
                title, body, links = parser.parsed()
                if body:
                    self.index.upsert(url,title,body)
                    indexed += 1
                if depth < 2:
                    for link in links:
                        if link not in self.visited and host_is_allowed(urllib.parse.urlsplit(link).hostname or "", self.domains):
                            queue.append((link,depth+1))
            except Exception:
                failed += 1
            if queue:
                time.sleep(self.delay_seconds)
        return {"visited":len(self.visited),"indexed":indexed,"failed":failed}


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def make_handler(index: SearchIndex, api_key: str):
    class Handler(BaseHTTPRequestHandler):
        server_version = "NewStarSearch/0.1"
        def log_message(self, fmt, *args):
            print("[new-star-search] " + (fmt % args), flush=True)
        def send_json(self, status, payload):
            encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type","application/json; charset=utf-8")
            self.send_header("Content-Length",str(len(encoded)))
            self.send_header("Cache-Control","no-store")
            self.end_headers()
            self.wfile.write(encoded)
        def do_GET(self):
            if self.path != "/healthz":
                self.send_json(404,{"ok":False,"error":"not found"})
                return
            self.send_json(200,{"ok":True,"service":"new-star-search","indexed_pages":index.page_count()})
        def do_POST(self):
            if self.path != "/search":
                self.send_json(404,{"ok":False,"error":"not found"})
                return
            scheme,_,token=self.headers.get("Authorization","").partition(" ")
            import hmac
            if scheme.lower()!="bearer" or not api_key or not hmac.compare_digest(token,api_key):
                self.send_json(401,{"ok":False,"error":"unauthorized"})
                return
            try:
                length=int(self.headers.get("Content-Length","0"))
                if length<1 or length>16384:
                    raise ValueError("request body must be between 1 and 16384 bytes")
                payload=json.loads(self.rfile.read(length).decode("utf-8"))
                query=payload.get("query") if isinstance(payload,dict) else None
                if not isinstance(query,str) or not query.strip() or len(query)>500:
                    raise ValueError("query must contain 1 to 500 characters")
                limit=payload.get("limit",5)
                if isinstance(limit,bool) or not isinstance(limit,int) or not 1<=limit<=10:
                    raise ValueError("limit must be an integer from 1 to 10")
                results=index.search(query,limit)
                self.send_json(200,{"ok":True,"results":results})
            except (ValueError,UnicodeDecodeError,json.JSONDecodeError) as exc:
                self.send_json(400,{"ok":False,"error":str(exc)})
            except Exception:
                self.send_json(500,{"ok":False,"error":"search request failed"})
    return Handler


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db",default=os.environ.get("NEW_STAR_SEARCH_DB","var/new-star-search.sqlite3"))
    commands=parser.add_subparsers(dest="command",required=True)
    serve=commands.add_parser("serve")
    serve.add_argument("--host",default=os.environ.get("NEW_STAR_SEARCH_HOST","127.0.0.1"))
    serve.add_argument("--port",type=int,default=int(os.environ.get("NEW_STAR_SEARCH_PORT","8081")))
    crawl=commands.add_parser("crawl")
    crawl.add_argument("--seed",action="append",required=True)
    crawl.add_argument("--max-pages",type=int,default=100)
    crawl.add_argument("--delay-seconds",type=float,default=1.0)
    args=parser.parse_args()
    index=SearchIndex(args.db)
    if args.command=="crawl":
        stats=SafeCrawler(index,allowed_domains(),args.max_pages,args.delay_seconds).crawl(args.seed)
        print(json.dumps(stats))
        return 0
    token=os.environ.get("NEW_STAR_SEARCH_API_KEY","")
    if not token:
        parser.error("NEW_STAR_SEARCH_API_KEY is required for the search API")
    if args.host not in ("127.0.0.1","::1","localhost") and not token:
        parser.error("an API key is required outside loopback")
    server=ThreadingHTTPServer((args.host,args.port),make_handler(index,token))
    print(f"New Star Search listening on http://{args.host}:{args.port}",flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()
    return 0

if __name__=="__main__":
    raise SystemExit(main())
