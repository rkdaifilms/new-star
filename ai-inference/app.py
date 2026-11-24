"""New Star's self-hosted, CPU-first, OpenAI-compatible text inference service.

Model weights are intentionally not bundled. Mount a licensed GGUF model at MODEL_PATH.
"""
from __future__ import annotations

import hmac
import os
from contextlib import asynccontextmanager
from pathlib import Path
from threading import BoundedSemaphore
from typing import Literal

from fastapi import Depends, FastAPI, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field

MODEL_PATH = Path(os.getenv("MODEL_PATH", "/models/model.gguf"))
MODEL_ID = os.getenv("MODEL_ID", "new-star-local")
MODEL_TOKEN = os.getenv("MODEL_TOKEN", "")
N_CTX = int(os.getenv("N_CTX", "4096"))
N_THREADS = max(1, int(os.getenv("N_THREADS", str(os.cpu_count() or 2))))
MAX_TOKENS = int(os.getenv("MAX_TOKENS", "2048"))
MAX_CONCURRENT = max(1, int(os.getenv("MAX_CONCURRENT", "1")))

_model = None
_model_error: str | None = None
_slots = BoundedSemaphore(MAX_CONCURRENT)
_bearer = HTTPBearer(auto_error=False)


class Message(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=8000)


class ChatRequest(BaseModel):
    model: str | None = None
    messages: list[Message] = Field(min_length=1, max_length=20)
    temperature: float = Field(default=0.4, ge=0.0, le=2.0)
    max_tokens: int = Field(default=512, ge=1, le=MAX_TOKENS)
    stream: bool = False


def require_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> None:
    if not MODEL_TOKEN:
        raise HTTPException(status_code=503, detail="Inference service is not configured.")
    supplied = credentials.credentials if credentials else ""
    if not hmac.compare_digest(supplied, MODEL_TOKEN):
        raise HTTPException(status_code=401, detail="Invalid bearer token.")


@asynccontextmanager
async def lifespan(_: FastAPI):
    global _model, _model_error
    if not MODEL_TOKEN:
        _model_error = "MODEL_TOKEN is not configured"
    elif not MODEL_PATH.is_file():
        _model_error = "Model file is missing"
    else:
        try:
            from llama_cpp import Llama

            _model = Llama(
                model_path=str(MODEL_PATH),
                n_ctx=N_CTX,
                n_threads=N_THREADS,
                n_gpu_layers=0,
                verbose=False,
            )
            _model_error = None
        except Exception as exc:  # Keep the health endpoint available for diagnosis.
            _model_error = type(exc).__name__
    yield
    _model = None


app = FastAPI(title="New Star CPU Inference", version="0.1.0", lifespan=lifespan)


@app.get("/healthz")
def healthz():
    return {
        "ok": _model is not None,
        "service": "new-star-cpu-inference",
        "engine": "llama.cpp",
        "device": "cpu",
        "model": MODEL_ID,
        "model_loaded": _model is not None,
        "reason": _model_error,
    }


@app.get("/v1/models", dependencies=[Depends(require_token)])
def list_models():
    if _model is None:
        raise HTTPException(status_code=503, detail="Model is not ready.")
    return {"object": "list", "data": [{"id": MODEL_ID, "object": "model", "owned_by": "new-star"}]}


@app.post("/v1/chat/completions", dependencies=[Depends(require_token)])
def chat_completions(payload: ChatRequest):
    if _model is None:
        raise HTTPException(status_code=503, detail="Model is not ready.")
    if payload.stream:
        raise HTTPException(status_code=400, detail="Streaming is not enabled in this first CPU release.")
    if payload.model and payload.model != MODEL_ID:
        raise HTTPException(status_code=404, detail="Requested model is not available.")
    if sum(len(message.content) for message in payload.messages) > 20000:
        raise HTTPException(status_code=413, detail="Conversation is too large.")
    if not _slots.acquire(blocking=False):
        raise HTTPException(status_code=429, detail="CPU inference is busy. Retry shortly.")
    try:
        result = _model.create_chat_completion(
            messages=[message.model_dump() for message in payload.messages],
            temperature=payload.temperature,
            max_tokens=payload.max_tokens,
        )
        choice = result["choices"][0]
        return {
            "id": result.get("id", "new-star-local"),
            "object": "chat.completion",
            "created": result.get("created", 0),
            "model": MODEL_ID,
            "choices": [{
                "index": 0,
                "message": choice.get("message", {"role": "assistant", "content": ""}),
                "finish_reason": choice.get("finish_reason", "stop"),
            }],
            "usage": result.get("usage", {}),
        }
    except HTTPException:
        raise
    except Exception as exc:
        # Do not return prompt, model internals, or secrets to clients.
        raise HTTPException(status_code=500, detail="Local model inference failed.") from exc
    finally:
        _slots.release()
