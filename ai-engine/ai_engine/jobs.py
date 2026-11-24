"""Durable SQLite queue with leases, idempotency, and bounded retries."""
from __future__ import annotations
import json, os, sqlite3, time, uuid
from pathlib import Path

class JobQueue:
    def __init__(self, database=None, max_pending=1000, lease_seconds=300, max_attempts=3):
        self.database = Path(database or os.environ.get("NEW_STAR_AI_QUEUE_DB", "var/new-star-jobs.sqlite3"))
        self.database.parent.mkdir(parents=True, exist_ok=True)
        self.max_pending = max(1, max_pending)
        self.lease_seconds = max(30, lease_seconds)
        self.max_attempts = max(1, max_attempts)
        self._initialize()

    def _connect(self):
        db = sqlite3.connect(self.database, timeout=30, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=30000")
        return db

    def _initialize(self):
        with self._connect() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, client_id TEXT NOT NULL, idempotency_key TEXT,
                kind TEXT NOT NULL, payload TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('queued','running','succeeded','failed')),
                attempts INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL,
                available_at REAL NOT NULL, lease_until REAL, worker_id TEXT,
                result TEXT, error TEXT, created_at REAL NOT NULL, updated_at REAL NOT NULL,
                UNIQUE(client_id,idempotency_key))""")
            db.execute("CREATE INDEX IF NOT EXISTS jobs_claim_idx ON jobs(status,available_at,created_at)")

    def enqueue(self, client_id, payload, idempotency_key=None):
        now, job_id = time.time(), "job_" + uuid.uuid4().hex
        encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            if idempotency_key:
                old = db.execute("SELECT * FROM jobs WHERE client_id=? AND idempotency_key=?", (client_id,idempotency_key)).fetchone()
                if old:
                    db.commit()
                    return self._public(old), False
            pending = db.execute("SELECT COUNT(*) FROM jobs WHERE status IN ('queued','running')").fetchone()[0]
            if pending >= self.max_pending:
                db.rollback()
                raise OverflowError("job queue is at capacity")
            db.execute("""INSERT INTO jobs (id,client_id,idempotency_key,kind,payload,status,attempts,
                max_attempts,available_at,created_at,updated_at) VALUES (?,?,?,?,?,'queued',0,?,?,?,?)""",
                (job_id,client_id,idempotency_key,"chat",encoded,self.max_attempts,now,now,now))
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            db.commit()
            return self._public(row), True

    def get(self, job_id, client_id):
        with self._connect() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=? AND client_id=?", (job_id,client_id)).fetchone()
            return self._public(row) if row else None

    def claim(self, worker_id=None):
        now, owner = time.time(), worker_id or "worker_" + uuid.uuid4().hex
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute("""UPDATE jobs SET status='queued',worker_id=NULL,lease_until=NULL,available_at=?,updated_at=?
                WHERE status='running' AND lease_until<? AND attempts<max_attempts""", (now,now,now))
            db.execute("""UPDATE jobs SET status='failed',error='worker lease expired; retry limit reached',
                worker_id=NULL,lease_until=NULL,updated_at=? WHERE status='running' AND lease_until<? AND attempts>=max_attempts""", (now,now))
            row = db.execute("SELECT * FROM jobs WHERE status='queued' AND available_at<=? ORDER BY created_at LIMIT 1", (now,)).fetchone()
            if not row:
                db.commit()
                return None
            changed = db.execute("""UPDATE jobs SET status='running',attempts=attempts+1,worker_id=?,lease_until=?,updated_at=?
                WHERE id=? AND status='queued'""", (owner,now+self.lease_seconds,now,row["id"])).rowcount
            if changed != 1:
                db.rollback()
                return None
            claimed = db.execute("SELECT * FROM jobs WHERE id=?", (row["id"],)).fetchone()
            db.commit()
            return {"id":claimed["id"],"client_id":claimed["client_id"],"kind":claimed["kind"],
                "payload":json.loads(claimed["payload"]),"attempts":claimed["attempts"],
                "max_attempts":claimed["max_attempts"],"worker_id":owner}

    def succeed(self, job_id, worker_id, result):
        now = time.time()
        encoded = json.dumps(result, ensure_ascii=False, separators=(",", ":"))
        with self._connect() as db:
            return db.execute("""UPDATE jobs SET status='succeeded',result=?,error=NULL,lease_until=NULL,
                worker_id=NULL,updated_at=? WHERE id=? AND status='running' AND worker_id=?""",
                (encoded,now,job_id,worker_id)).rowcount == 1

    def fail(self, job_id, worker_id, error, retry_delay=2.0):
        now = time.time()
        with self._connect() as db:
            row = db.execute("SELECT attempts,max_attempts FROM jobs WHERE id=? AND status='running' AND worker_id=?", (job_id,worker_id)).fetchone()
            if not row:
                return False
            message = str(error)[:500]
            if row["attempts"] < row["max_attempts"]:
                delay = min(300.0,max(0.5,retry_delay)*(2**(row["attempts"]-1)))
                db.execute("""UPDATE jobs SET status='queued',error=?,available_at=?,lease_until=NULL,worker_id=NULL,updated_at=? WHERE id=?""", (message,now+delay,now,job_id))
            else:
                db.execute("""UPDATE jobs SET status='failed',error=?,lease_until=NULL,worker_id=NULL,updated_at=? WHERE id=?""", (message,now,job_id))
            return True

    def counts(self):
        with self._connect() as db:
            rows = db.execute("SELECT status,COUNT(*) AS count FROM jobs GROUP BY status").fetchall()
        result = {"queued":0,"running":0,"succeeded":0,"failed":0}
        result.update({row["status"]:row["count"] for row in rows})
        return result

    @staticmethod
    def _public(row):
        result = {"id":row["id"],"kind":row["kind"],"status":row["status"],"attempts":row["attempts"],
            "max_attempts":row["max_attempts"],"created_at":row["created_at"],"updated_at":row["updated_at"]}
        if row["result"]:
            result["result"] = json.loads(row["result"])
        if row["error"]:
            result["error"] = row["error"]
        return result
