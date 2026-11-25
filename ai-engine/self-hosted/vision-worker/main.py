import base64
import io
import os
import threading
from typing import Optional

import torch
from fastapi import FastAPI, Header, HTTPException
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field
from transformers import AutoProcessor, AutoModelForVision2Seq

app = FastAPI(title="New Star Private Vision Worker", docs_url=None, redoc_url=None)
API_KEY = os.getenv("NEW_STAR_VISION_API_KEY", "")
MODEL_ID = os.getenv("NEW_STAR_VISION_MODEL", "HuggingFaceTB/SmolVLM2-2.2B-Instruct")
CACHE = os.getenv("NEW_STAR_VISION_CACHE", "/models/huggingface")
_lock = threading.Lock()
_model = None
_processor = None


def auth(value: Optional[str]):
    if len(API_KEY) < 32:
        raise HTTPException(503, "Private vision API key is not configured")
    if value != "Bearer " + API_KEY:
        raise HTTPException(401, "Unauthorized")


def load_model():
    global _model, _processor
    with _lock:
        if _model is None:
            if not torch.cuda.is_available():
                raise RuntimeError("A CUDA GPU is required by the private vision worker")
            _processor = AutoProcessor.from_pretrained(MODEL_ID, cache_dir=CACHE)
            _model = AutoModelForVision2Seq.from_pretrained(
                MODEL_ID, torch_dtype=torch.float16, device_map="auto", cache_dir=CACHE
            )
            _model.eval()
    return _model, _processor


class VisionRequest(BaseModel):
    image: str = Field(min_length=100, max_length=8500000)
    mime_type: str = Field(pattern="^(image/png|image/jpeg|image/webp)$")
    prompt: str = Field(default="Describe the visible contents of this image.", min_length=1, max_length=3000)


@app.get("/health")
def health():
    return {"ok": True, "service": "new-star-private-vision", "model_loaded": _model is not None, "cuda": torch.cuda.is_available()}


@app.post("/v1/vision/analyze")
def analyze(request: VisionRequest, authorization: Optional[str] = Header(default=None)):
    auth(authorization)
    try:
        raw = base64.b64decode(request.image, validate=True)
        if len(raw) > 6 * 1024 * 1024:
            raise HTTPException(413, "Image exceeds the 6 MiB limit")
        image = Image.open(io.BytesIO(raw)).convert("RGB")
        image.thumbnail((1280, 1280))
    except HTTPException:
        raise
    except (ValueError, UnidentifiedImageError):
        raise HTTPException(400, "Invalid image data")
    try:
        model, processor = load_model()
        messages = [{"role": "user", "content": [{"type": "image", "image": image}, {"type": "text", "text": request.prompt}]}]
        prompt = processor.apply_chat_template(messages, add_generation_prompt=True)
        inputs = processor(text=prompt, images=[image], return_tensors="pt").to(model.device)
        with torch.inference_mode():
            generated = model.generate(**inputs, max_new_tokens=450, do_sample=False)
        answer = processor.batch_decode(generated, skip_special_tokens=True)[0].strip()
        if not answer:
            raise RuntimeError("Vision model returned an empty answer")
        return {"ok": True, "answer": answer[-12000:], "model": MODEL_ID}
    except HTTPException:
        raise
    except Exception as error:
        raise HTTPException(503, "Private vision inference failed: " + str(error)[:180])
