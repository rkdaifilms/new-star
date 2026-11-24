"""API authentication, token-bucket limits, and privacy-safe JSONL usage logging."""
from __future__ import annotations
import hashlib, hmac, json, logging, os, threading, time
from logging.handlers import RotatingFileHandler

def load_api_keys(raw=None):
    value = os.environ.get("NEW_STAR_AI_API_KEYS", "") if raw is None else raw
    keys = {}
    for item in value.split(","):
        if not item.strip():
            continue
        client_id, sep, secret = item.strip().partition(":")
        if not sep or not client_id or not secret or len(client_id) > 80 or client_id in keys:
            raise ValueError("NEW_STAR_AI_API_KEYS must contain unique client_id:secret pairs")
        keys[client_id] = secret
    return keys

def authenticate_api_key(header, keys):
    scheme, _, supplied = header.partition(" ")
    if scheme.lower() != "bearer" or not supplied:
        return None
    digest = hashlib.sha256(supplied.encode()).digest()
    match = None
    for client_id, secret in keys.items():
        if hmac.compare_digest(digest, hashlib.sha256(secret.encode()).digest()):
            match = client_id
    return match

class TokenBucketLimiter:
    def __init__(self, capacity=60, refill_per_second=1.0):
        if capacity < 1 or refill_per_second <= 0:
            raise ValueError("rate limit settings must be positive")
        self.capacity, self.refill = capacity, refill_per_second
        self.buckets, self.lock = {}, threading.Lock()
    def allow(self, identity, now=None):
        current = time.monotonic() if now is None else now
        with self.lock:
            tokens, updated = self.buckets.get(identity, (float(self.capacity), current))
            tokens = min(float(self.capacity), tokens + max(0, current-updated)*self.refill)
            if tokens < 1:
                self.buckets[identity] = (tokens, current)
                return False
            self.buckets[identity] = (tokens-1, current)
            if len(self.buckets) > 10000:
                cutoff = current - max(60, self.capacity/self.refill*2)
                self.buckets = {k:v for k,v in self.buckets.items() if v[1] >= cutoff}
            return True

class UsageLogger:
    def __init__(self, path=None):
        self.path = os.environ.get("NEW_STAR_AI_USAGE_LOG", "") if path is None else path
        self.logger = logging.getLogger("new_star_ai_usage_" + str(id(self)))
        self.logger.setLevel(logging.INFO)
        self.logger.propagate = False
        if self.path:
            handler = RotatingFileHandler(self.path, maxBytes=5000000, backupCount=3, encoding="utf-8")
            handler.setFormatter(logging.Formatter("%(message)s"))
            self.logger.addHandler(handler)
    def record(self, request_id, client_id, route, status, latency_ms, model=""):
        if self.path:
            event = {"timestamp":int(time.time()),"request_id":request_id,"client_id":client_id,
                     "route":route,"status":int(status),"latency_ms":max(0,int(latency_ms)),
                     "model":str(model)[:120]}
            self.logger.info(json.dumps(event, separators=(",",":"), ensure_ascii=True))


class SQLiteTokenBucketLimiter:
    """Persistent per-client token bucket shared by API processes on one host."""
    def __init__(self, database=None, capacity=60, refill_per_second=1.0):
        import sqlite3
        if capacity < 1 or refill_per_second <= 0:
            raise ValueError("rate limit settings must be positive")
        self.sqlite3 = sqlite3
        self.database = os.environ.get("NEW_STAR_AI_RATE_LIMIT_DB", database or os.environ.get("NEW_STAR_AI_QUEUE_DB", "var/new-star-jobs.sqlite3"))
        from pathlib import Path
        Path(self.database).parent.mkdir(parents=True, exist_ok=True)
        self.capacity = int(capacity)
        self.refill = float(refill_per_second)
        with self._connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("CREATE TABLE IF NOT EXISTS api_rate_buckets (identity TEXT PRIMARY KEY, tokens REAL NOT NULL, updated_at REAL NOT NULL)")

    def _connect(self):
        db = self.sqlite3.connect(self.database, timeout=30, isolation_level=None)
        db.execute("PRAGMA busy_timeout=30000")
        return db

    def allow(self, identity, now=None):
        current = time.time() if now is None else float(now)
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT tokens,updated_at FROM api_rate_buckets WHERE identity=?", (identity,)).fetchone()
            tokens, updated = (float(self.capacity), current) if row is None else (float(row[0]), float(row[1]))
            tokens = min(float(self.capacity), tokens + max(0.0, current-updated)*self.refill)
            allowed = tokens >= 1.0
            if allowed:
                tokens -= 1.0
            db.execute("INSERT INTO api_rate_buckets(identity,tokens,updated_at) VALUES(?,?,?) ON CONFLICT(identity) DO UPDATE SET tokens=excluded.tokens,updated_at=excluded.updated_at",
                       (identity,tokens,current))
            if current % 97 < 0.01:
                cutoff = current - max(3600.0, self.capacity/self.refill*10)
                db.execute("DELETE FROM api_rate_buckets WHERE updated_at < ?", (cutoff,))
            db.commit()
            return allowed
