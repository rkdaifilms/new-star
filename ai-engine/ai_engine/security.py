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
