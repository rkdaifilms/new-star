"""Queue-depth autoscaler for a single-host New Star worker pool."""
from __future__ import annotations
import os
import signal
import subprocess
import sys
import time
from .jobs import JobQueue


def desired_worker_count(queued: int, running: int, max_workers: int, jobs_per_worker: int) -> int:
    active = max(0, queued) + max(0, running)
    if active == 0:
        return 0
    return min(max(1, max_workers), max(1, (active + max(1, jobs_per_worker) - 1) // max(1, jobs_per_worker)))


def supervise() -> int:
    max_workers = max(1, int(os.environ.get("NEW_STAR_AI_WORKER_MAX_PROCESSES", str(min(4, os.cpu_count() or 1)))))
    jobs_per_worker = max(1, int(os.environ.get("NEW_STAR_AI_WORKER_JOBS_PER_PROCESS", "2")))
    poll_seconds = max(1.0, float(os.environ.get("NEW_STAR_AI_AUTOSCALER_POLL_SECONDS", "3")))
    idle_timeout = max(10.0, float(os.environ.get("NEW_STAR_AI_WORKER_IDLE_TIMEOUT_SECONDS", "60")))
    queue = JobQueue()
    children: list[subprocess.Popen] = []
    stopping = False

    def request_stop(_signum, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    print(f"New Star worker autoscaler: max={max_workers}, jobs_per_process={jobs_per_worker}", flush=True)
    try:
        while not stopping:
            children = [child for child in children if child.poll() is None]
            counts = queue.counts()
            target = desired_worker_count(counts["queued"], counts["running"], max_workers, jobs_per_worker)
            while len(children) < target:
                command = [sys.executable, "-m", "ai_engine.worker", "--idle-timeout", str(idle_timeout)]
                child = subprocess.Popen(command, env=os.environ.copy())
                children.append(child)
                print(f"Started New Star worker pid={child.pid}; target={target}", flush=True)
            time.sleep(poll_seconds)
    finally:
        for child in children:
            if child.poll() is None:
                child.terminate()
        deadline = time.monotonic() + 10
        for child in children:
            remaining = max(0.1, deadline - time.monotonic())
            try:
                child.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=2)
    return 0


def main() -> int:
    return supervise()


if __name__ == "__main__":
    raise SystemExit(main())
