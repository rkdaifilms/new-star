import base64
import io
import os
import threading

import torch
from diffusers import AutoPipelineForText2Image
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from typing import Optional
from PIL import Image

app = FastAPI(title="New Star Private Image Worker", docs_url=None, redoc_url=None)
API_KEY = os.getenv("NEW_STAR_IMAGE_API_KEY", "")
MODEL_ID = os.getenv("NEW_STAR_IMAGE_MODEL", "stabilityai/sdxl-turbo")
MODEL_CACHE = os.getenv("NEW_STAR_IMAGE_CACHE", "/models/huggingface")
_pipeline = None
_pipeline_lock = threading.Lock()
_generation_lock = threading.Lock()
RATIOS = {"1:1": (1, 1), "3:4": (3, 4), "4:3": (4, 3), "9:16": (9, 16), "16:9": (16, 9)}


def require_auth(authorization: Optional[str]) -> None:
    if len(API_KEY) < 32:
        raise HTTPException(status_code=503, detail="Private image worker API key is not configured")
    if authorization != "Bearer " + API_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized")


def pipeline():
    global _pipeline
    with _pipeline_lock:
        if _pipeline is None:
            if not torch.cuda.is_available():
                raise HTTPException(status_code=503, detail="A CUDA GPU is required by the private image worker")
            os.makedirs(MODEL_CACHE, exist_ok=True)
            try:
                _pipeline = AutoPipelineForText2Image.from_pretrained(
                    MODEL_ID,
                    torch_dtype=torch.float16,
                    variant="fp16",
                    use_safetensors=True,
                    cache_dir=MODEL_CACHE,
                ).to("cuda")
                _pipeline.enable_attention_slicing()
            except Exception as exc:
                raise HTTPException(status_code=503, detail="Private image model weights could not be loaded") from exc
    return _pipeline


class ImageRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=1800)
    aspect_ratio: str = Field(default="1:1", min_length=3, max_length=5)
    image_size: str = Field(default="1K", pattern="^(1K|2K|4K)$")


@app.get("/health")
def health():
    return {"ok": True, "service": "new-star-private-image", "model_loaded": _pipeline is not None, "cuda": torch.cuda.is_available()}


@app.post("/v1/images/generations")
def generate(request: ImageRequest, authorization: Optional[str] = Header(default=None)):
    require_auth(authorization)
    if request.aspect_ratio not in RATIOS:
        raise HTTPException(status_code=400, detail="Unsupported aspect ratio")
    if request.image_size != "1K":
        raise HTTPException(status_code=422, detail="This GPU worker currently supports 1K image output only")
    ratio_w, ratio_h = RATIOS[request.aspect_ratio]
    # Keep the long edge at 1024 and round dimensions to multiples of 8.
    if ratio_w >= ratio_h:
        width, height = 1024, max(512, int(1024 * ratio_h / ratio_w) // 8 * 8)
    else:
        height, width = 1024, max(512, int(1024 * ratio_w / ratio_h) // 8 * 8)
    model = pipeline()
    try:
        with _generation_lock:
            result = model(
                prompt=request.prompt,
                width=width,
                height=height,
                num_inference_steps=4,
                guidance_scale=0.0,
            )
        image = result.images[0]
        output = io.BytesIO()
        image.save(output, format="PNG", optimize=True)
        encoded = base64.b64encode(output.getvalue()).decode("ascii")
        if len(encoded) > 12_000_000:
            raise HTTPException(status_code=502, detail="Generated image exceeds the response size limit")
        return {"image": encoded, "mimeType": "image/png", "width": image.width, "height": image.height, "model": MODEL_ID}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Private image generation failed") from exc
