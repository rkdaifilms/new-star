import json
import os
import queue
import threading
import time
import uuid
from pathlib import Path
from typing import Optional

import imageio.v2 as imageio
import torch
from diffusers import CogVideoXPipeline
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

app = FastAPI(title="New Star Private Video Worker", docs_url=None, redoc_url=None)
API_KEY = os.getenv("NEW_STAR_VIDEO_API_KEY", "")
MODEL_ID = os.getenv("NEW_STAR_VIDEO_MODEL", "THUDM/CogVideoX-2b")
MODEL_CACHE = Path(os.getenv("NEW_STAR_VIDEO_CACHE", "/models/huggingface"))
JOB_DIR = Path(os.getenv("NEW_STAR_VIDEO_JOBS", "/data/jobs"))
MAX_PROMPT = 1800
FPS = 8
FRAME_COUNT = 49
_jobs: dict[str, dict] = {}
_jobs_lock = threading.Lock()
_queue: queue.Queue[str] = queue.Queue(maxsize=2)
_pipeline = None
_pipeline_lock = threading.Lock()


def require_auth(authorization: Optional[str]) -> None:
    if len(API_KEY) < 32:
        raise HTTPException(status_code=503, detail="Private video worker API key is not configured")
    if authorization != "Bearer " + API_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized")


def persist(job_id: str) -> None:
    JOB_DIR.mkdir(parents=True, exist_ok=True)
    temporary = JOB_DIR / (job_id + ".json.tmp")
    final = JOB_DIR / (job_id + ".json")
    temporary.write_text(json.dumps(_jobs[job_id]), encoding="utf-8")
    temporary.replace(final)


def load_pipeline():
    global _pipeline
    with _pipeline_lock:
        if _pipeline is None:
            if not torch.cuda.is_available():
                raise RuntimeError("A CUDA GPU is required by the private video worker")
            MODEL_CACHE.mkdir(parents=True, exist_ok=True)
            pipe = CogVideoXPipeline.from_pretrained(
                MODEL_ID,
                torch_dtype=torch.float16,
                cache_dir=str(MODEL_CACHE),
                use_safetensors=True,
            )
            pipe.enable_model_cpu_offload()
            pipe.vae.enable_slicing()
            pipe.vae.enable_tiling()
            _pipeline = pipe
    return _pipeline


def render(job_id: str) -> None:
    with _jobs_lock:
        job = _jobs[job_id]
        job["status"] = "in_progress"
        job["updated_at"] = int(time.time())
        persist(job_id)
    try:
        pipe = load_pipeline()
        prompt = _jobs[job_id]["prompt"]
        ratio = _jobs[job_id]["aspect_ratio"]
        with _pipeline_lock:
            result = pipe(
                prompt=prompt,
                num_frames=FRAME_COUNT,
                num_inference_steps=30,
                guidance_scale=6.0,
                generator=torch.Generator(device="cuda").manual_seed(int(job_id.replace("-", "")[:8], 16)),
            )
        frames = result.frames[0]
        if len(frames) != FRAME_COUNT:
            raise RuntimeError("Video model returned an unexpected frame count")
        output = JOB_DIR / (job_id + ".mp4")
        JOB_DIR.mkdir(parents=True, exist_ok=True)
        with imageio.get_writer(str(output), fps=FPS, codec="libx264", quality=8, macro_block_size=16) as writer:
            for frame in frames:
                if ratio == "9:16":
                    width, height = frame.size
                    target_ratio = 9 / 16
                    current_ratio = width / height
                    if current_ratio > target_ratio:
                        crop_width = int(height * target_ratio)
                        left = (width - crop_width) // 2
                        frame = frame.crop((left, 0, left + crop_width, height))
                    else:
                        crop_height = int(width / target_ratio)
                        top = (height - crop_height) // 2
                        frame = frame.crop((0, top, width, top + crop_height))
                    frame = frame.resize((480, 848))
                else:
                    frame = frame.resize((848, 480))
                writer.append_data(__import__("numpy").asarray(frame.convert("RGB")))
        size = output.stat().st_size
        if size <= 0 or size > 50 * 1024 * 1024:
            raise RuntimeError("Generated video is empty or exceeds the 50 MiB output limit")
        with _jobs_lock:
            _jobs[job_id].update({"status": "completed", "output": output.name, "bytes": size, "updated_at": int(time.time())})
            persist(job_id)
    except Exception as exc:
        with _jobs_lock:
            _jobs[job_id].update({"status": "failed", "failure_code": "PRIVATE_VIDEO_GENERATION_FAILED", "error": str(exc)[:240], "updated_at": int(time.time())})
            persist(job_id)


def queue_worker() -> None:
    while True:
        job_id = _queue.get()
        try:
            render(job_id)
        finally:
            _queue.task_done()


threading.Thread(target=queue_worker, daemon=True, name="new-star-video-queue").start()


class VideoRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=MAX_PROMPT)
    aspect_ratio: str = Field(default="16:9", pattern="^(16:9|9:16)$")
    resolution: str = Field(default="480p", pattern="^480p$")
    duration: str = Field(default="6s", pattern="^6s$")


@app.get("/health")
def health():
    return {"ok": True, "service": "new-star-private-video", "model_loaded": _pipeline is not None, "cuda": torch.cuda.is_available(), "queued": _queue.qsize()}


@app.post("/v1/videos/generations", status_code=202)
def create_video(request: VideoRequest, authorization: Optional[str] = Header(default=None)):
    require_auth(authorization)
    if not torch.cuda.is_available():
        raise HTTPException(status_code=503, detail="A CUDA GPU is required by the private video worker")
    job_id = str(uuid.uuid4())
    JOB_DIR.mkdir(parents=True, exist_ok=True)
    with _jobs_lock:
        _jobs[job_id] = {
            "id": job_id,
            "status": "queued",
            "prompt": request.prompt.strip(),
            "aspect_ratio": request.aspect_ratio,
            "resolution": request.resolution,
            "duration": request.duration,
            "model": MODEL_ID,
            "created_at": int(time.time()),
            "updated_at": int(time.time()),
        }
        persist(job_id)
    try:
        _queue.put_nowait(job_id)
    except queue.Full:
        with _jobs_lock:
            _jobs[job_id].update({"status": "failed", "failure_code": "VIDEO_QUEUE_FULL", "updated_at": int(time.time())})
            persist(job_id)
        raise HTTPException(status_code=429, detail="Private video generation queue is full; try again later")
    return {"ok": True, "job_id": job_id, "status": "queued", "model": MODEL_ID}


@app.get("/v1/videos/jobs/{job_id}")
def get_video_job(job_id: str, authorization: Optional[str] = Header(default=None)):
    require_auth(authorization)
    try:
        uuid.UUID(job_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid video job ID")
    with _jobs_lock:
        job = _jobs.get(job_id)
        if job is None:
            saved = JOB_DIR / (job_id + ".json")
            if saved.exists():
                job = json.loads(saved.read_text(encoding="utf-8"))
                _jobs[job_id] = job
        if job is None:
            raise HTTPException(status_code=404, detail="Video job not found")
        return {key: value for key, value in job.items() if key not in {"prompt", "error"}}


@app.get("/v1/videos/jobs/{job_id}/output")
def get_video_output(job_id: str, authorization: Optional[str] = Header(default=None)):
    require_auth(authorization)
    try:
        uuid.UUID(job_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid video job ID")
    with _jobs_lock:
        job = _jobs.get(job_id)
        if job is None:
            raise HTTPException(status_code=404, detail="Video job not found")
        if job.get("status") != "completed":
            raise HTTPException(status_code=409, detail="Video job is not completed")
        output = JOB_DIR / (job_id + ".mp4")
        if not output.is_file():
            raise HTTPException(status_code=404, detail="Video output file is missing")
    return FileResponse(output, media_type="video/mp4", filename="new-star-ai-video.mp4")
