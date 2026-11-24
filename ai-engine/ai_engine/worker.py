"""Run durable New Star background inference jobs."""
from __future__ import annotations
import argparse, os, socket, threading, time, uuid
from .config import EngineSettings
from .inference import ChatInference
from .jobs import JobQueue
from .registry import ModelRegistry

def run_worker(once=False, poll_seconds=1.0, idle_timeout=0.0):
    settings = EngineSettings.from_env()
    registry = ModelRegistry.from_json_file(settings.models_file, settings)
    inference = ChatInference(registry)
    queue = JobQueue()
    worker_id = f"{socket.gethostname()}-{os.getpid()}-{uuid.uuid4().hex[:8]}"
    while True:
        job = queue.claim(worker_id)
        if job is None:
            if once:
                return 0
            time.sleep(max(0.1,poll_seconds))
            continue
        idle_since = time.monotonic()
        try:
            if job["kind"] != "chat":
                raise ValueError("unsupported background job kind")
            stop_heartbeat = threading.Event()
            lease_lost = threading.Event()
            def keep_lease():
                while not stop_heartbeat.wait(max(5, queue.lease_seconds // 3)):
                    if not queue.heartbeat(job["id"], worker_id):
                        lease_lost.set()
                        return
            heartbeat_thread = threading.Thread(target=keep_lease, daemon=True)
            heartbeat_thread.start()
            try:
                result = inference.complete(job["payload"])
            finally:
                stop_heartbeat.set()
                heartbeat_thread.join(timeout=2)
            if lease_lost.is_set():
                raise RuntimeError("job lease was lost while inference was running")
            if not queue.succeed(job["id"],worker_id,result):
                raise RuntimeError("job lease was lost before completion")
        except Exception as exc:
            queue.fail(job["id"],worker_id,f"{type(exc).__name__}: {exc}")
        if once:
            return 0

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--once",action="store_true")
    parser.add_argument("--poll-seconds",type=float,default=float(os.environ.get("NEW_STAR_AI_WORKER_POLL_SECONDS","1")))
    parser.add_argument("--idle-timeout",type=float,default=0.0)
    args = parser.parse_args()
    return run_worker(args.once,args.poll_seconds,args.idle_timeout)

if __name__ == "__main__":
    raise SystemExit(main())
