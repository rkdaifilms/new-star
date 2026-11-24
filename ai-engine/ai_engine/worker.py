"""Run durable New Star background inference jobs."""
from __future__ import annotations
import argparse, os, socket, time, uuid
from .config import EngineSettings
from .inference import ChatInference
from .jobs import JobQueue
from .registry import ModelRegistry

def run_worker(once=False, poll_seconds=1.0):
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
        try:
            if job["kind"] != "chat":
                raise ValueError("unsupported background job kind")
            result = inference.complete(job["payload"])
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
    args = parser.parse_args()
    return run_worker(args.once,args.poll_seconds)

if __name__ == "__main__":
    raise SystemExit(main())
