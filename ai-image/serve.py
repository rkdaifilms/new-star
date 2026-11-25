"""Local authenticated HTTP API for New Star image checkpoints."""
from __future__ import annotations

import argparse
import base64
import io
import json
import os
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import torch
from PIL import Image

from generate import load_checkpoint
from model import DDPMSchedule, NewStarImageUNet
from text_conditioning import MAX_TEXT_TOKENS, encode_caption


def encode_png(image: torch.Tensor) -> str:
    if image.ndim != 3 or image.shape[0] != 3:
        raise ValueError("generated image must have shape [3, height, width]")
    pixels = image.detach().cpu().clamp(-1, 1).add(1).mul(127.5).byte()
    rendered = Image.frombytes(
        "RGB", (pixels.shape[2], pixels.shape[1]),
        bytes(pixels.permute(1, 2, 0).contiguous().view(-1).tolist()),
    )
    buffer = io.BytesIO()
    rendered.save(buffer, format="PNG")
    return base64.b64encode(buffer.getvalue()).decode("ascii")


def create_handler(model, schedule, image_size: int, device: str, api_key: str):
    generation_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        server_version = "NewStarImage/0.1"

        def send_json(self, status: int, payload: dict[str, Any]) -> None:
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            if self.path == "/health":
                self.send_json(200, {
                    "ok": True,
                    "model": "new-star-ddpm-text-conditioned" if model.text_vocab_size else "new-star-ddpm-unconditional",
                    "image_size": image_size,
                    "diffusion_steps": schedule.steps,
                })
            elif self.path == "/v1/models":
                self.send_json(200, {
                    "object": "list",
                    "data": [{
                        "id": "new-star-ddpm-text-conditioned" if model.text_vocab_size else "new-star-ddpm-unconditional",
                        "object": "model",
                        "owned_by": "new-star",
                        "capabilities": ["image_generation_text_to_image"] if model.text_vocab_size else ["image_generation_unconditional"],
                    }],
                })
            else:
                self.send_json(404, {"error": {"message": "Not found", "type": "not_found"}})

        def do_POST(self):
            if api_key:
                supplied = self.headers.get("Authorization", "")
                if not secrets.compare_digest(supplied, "Bearer " + api_key):
                    self.send_json(401, {"error": {"message": "Unauthorized", "type": "authentication_error"}})
                    return
            if self.path != "/v1/images/generations":
                self.send_json(404, {"error": {"message": "Not found", "type": "not_found"}})
                return
            try:
                content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                if content_type != "application/json":
                    raise ValueError("Content-Type must be application/json")
                if self.headers.get("Transfer-Encoding"):
                    raise ValueError("transfer-encoded request bodies are not supported")
                length = int(self.headers.get("Content-Length", "0"))
                if length < 2 or length > 4096:
                    raise ValueError("request body must be between 2 bytes and 4 KB")
                payload = json.loads(self.rfile.read(length))
                if not isinstance(payload, dict):
                    raise ValueError("request body must be a JSON object")
                if "prompt" in payload and not model.text_vocab_size:
                    raise ValueError("this checkpoint is unconditional and does not support text prompts")
                if model.text_vocab_size and ("prompt" not in payload or not isinstance(payload["prompt"], str)):
                    raise ValueError("a non-empty prompt is required by this text-conditioned checkpoint")
                unsupported = sorted(set(payload) - {"n", "seed", "size", "prompt", "guidance_scale"})
                if unsupported:
                    raise ValueError("unsupported request fields: " + ", ".join(unsupported))
                count = payload.get("n", 1)
                if isinstance(count, bool) or not isinstance(count, int) or not 1 <= count <= 4:
                    raise ValueError("n must be an integer from 1 to 4")
                requested_size = payload.get("size")
                if requested_size not in (None, image_size, f"{image_size}x{image_size}"):
                    raise ValueError(f"this checkpoint only supports {image_size}x{image_size} images")
                seed = payload.get("seed")
                if seed is not None and (
                    isinstance(seed, bool) or not isinstance(seed, int)
                    or not 0 <= seed <= 2**63 - 1
                ):
                    raise ValueError("seed must be an integer between 0 and 2^63-1")
                guidance_scale = payload.get("guidance_scale", 1.0)
                if isinstance(guidance_scale, bool) or not isinstance(guidance_scale, (int, float)) or not math.isfinite(float(guidance_scale)) or not 0.0 <= float(guidance_scale) <= 10.0:
                    raise ValueError("guidance_scale must be a finite number from 0 to 10")
                if not model.text_vocab_size and guidance_scale != 1.0:
                    raise ValueError("guidance_scale requires a text-conditioned checkpoint")
                text_tokens = None
                if model.text_vocab_size:
                    encoded = encode_caption(payload["prompt"])
                    text_tokens = torch.zeros((count, MAX_TEXT_TOKENS), dtype=torch.long, device=device)
                    text_tokens[:, :len(encoded)] = torch.tensor(encoded, dtype=torch.long, device=device)
                started = time.time()
                with generation_lock, torch.inference_mode():
                    generated = schedule.sample(
                        model, count, image_size, device, seed=seed, text_tokens=text_tokens,
                        guidance_scale=guidance_scale
                    )
                data = [{"b64_json": encode_png(image)} for image in generated]
                self.send_json(200, {
                    "created": int(started),
                    "model": "new-star-ddpm-text-conditioned" if model.text_vocab_size else "new-star-ddpm-unconditional",
                    "data": data,
                })
            except (ValueError, TypeError, json.JSONDecodeError) as exc:
                self.send_json(400, {"error": {"message": str(exc), "type": "invalid_request_error"}})
            except Exception:
                self.send_json(500, {"error": {"message": "Image generation failed", "type": "server_error"}})

        def log_message(self, fmt, *args):
            return

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8780)
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    api_key = os.environ.get("NEW_STAR_IMAGE_API_KEY", "").strip()
    if args.host not in {"127.0.0.1", "localhost", "::1"} and not api_key:
        parser.error("set NEW_STAR_IMAGE_API_KEY before binding to a non-loopback interface")
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    model, schedule, config = load_checkpoint(args.checkpoint, args.device)
    server = ThreadingHTTPServer(
        (args.host, args.port),
        create_handler(model, schedule, config["image_size"], args.device, api_key),
    )
    print(f"New Star image API listening on http://{args.host}:{args.port}")
    print("Text prompts are required for caption-conditioned checkpoints; unconditional checkpoints do not accept prompts.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
