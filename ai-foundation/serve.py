"""Local HTTP inference adapter for New Star's own experimental checkpoint.

This is a development server, not a production multi-tenant service. It binds
only to localhost by default. A non-loopback bind requires an API key.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import secrets
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import torch

from model import NewStarConfig, NewStarGPT
from tokenizer import tokenizer_from_dict


def format_chat_messages(messages: list[dict[str, str]]) -> str:
    if not isinstance(messages, list) or not messages:
        raise ValueError("messages must be a non-empty list")
    parts = []
    for item in messages:
        if not isinstance(item, dict):
            raise ValueError("each message must be an object")
        role = item.get("role")
        if not isinstance(role, str) or role not in {"system", "user", "assistant"}:
            raise ValueError("each message must have a supported role")
        content = item.get("content")
        if not isinstance(content, str) or not content.strip():
            raise ValueError("each message requires non-empty text content")
        parts.append(f"{role}: {content.strip()}")
    if messages[-1]["role"] != "user":
        raise ValueError("the final chat message must be from the user")
    return "\n".join(parts) + "\nassistant:"


def load_engine(checkpoint_path: str, device: str):
    checkpoint = torch.load(checkpoint_path, map_location=device, weights_only=True)
    if not isinstance(checkpoint, dict):
        raise ValueError("foundation checkpoint must contain a dictionary")
    checkpoint_format = checkpoint.get("format")
    if checkpoint_format not in {"new-star-unicode-char-gpt-v1", "new-star-byte-bpe-gpt-v1"}:
        raise ValueError("the local foundation server requires a supported New Star tokenizer checkpoint")
    raw_config = checkpoint.get("config")
    state_dict = checkpoint.get("state_dict")
    tokenizer_payload = checkpoint.get("tokenizer")
    if not isinstance(raw_config, dict):
        raise ValueError("foundation checkpoint is missing a valid model config")
    if not isinstance(state_dict, dict):
        raise ValueError("foundation checkpoint is missing a model state dictionary")
    if not isinstance(tokenizer_payload, dict):
        raise ValueError("foundation checkpoint is missing a tokenizer")
    try:
        config = NewStarConfig(**raw_config)
        tokenizer = tokenizer_from_dict(tokenizer_payload)
    except (TypeError, ValueError) as exc:
        raise ValueError("foundation checkpoint config or tokenizer is invalid") from exc
    if len(tokenizer) != config.vocab_size:
        raise ValueError(
            "foundation checkpoint tokenizer vocabulary does not match model vocab_size"
        )
    model = NewStarGPT(config).to(device)
    try:
        model.load_state_dict(state_dict, strict=True)
    except (RuntimeError, TypeError) as exc:
        raise ValueError("foundation checkpoint weights do not match the configured model") from exc
    model.eval()
    return model, tokenizer


def create_handler(model, tokenizer, model_name: str, device: str, api_key: str):
    # A single model instance is shared by ThreadingHTTPServer workers. Serialize
    # generation to bound peak memory and avoid concurrent GPU allocation spikes.
    inference_lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        server_version = "NewStarFoundation/0.1"

        def _send(self, status: int, payload: dict[str, Any]):
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            # Keep the health probe public for load balancers, but protect model
            # metadata just like inference endpoints when an API key is set.
            if self.path == "/health":
                self._send(200, {"ok": True, "model": model_name, "engine": "new-star-from-scratch"})
                return
            if self.path == "/v1/models" and api_key:
                supplied = self.headers.get("Authorization", "")
                expected = "Bearer " + api_key
                if not secrets.compare_digest(supplied, expected):
                    self._send(401, {"error": {"message": "Unauthorized", "type": "authentication_error"}})
                    return
            if self.path == "/v1/models":
                self._send(200, {
                    "object": "list",
                    "data": [{
                        "id": model_name,
                        "object": "model",
                        "created": int(time.time()),
                        "owned_by": "new-star",
                    }],
                })
            else:
                self._send(404, {"error": {"message": "Not found", "type": "not_found"}})

        def do_POST(self):
            if api_key:
                supplied = self.headers.get("Authorization", "")
                expected = "Bearer " + api_key
                if not secrets.compare_digest(supplied, expected):
                    self._send(401, {"error": {"message": "Unauthorized", "type": "authentication_error"}})
                    return
            if self.path not in {"/v1/completions", "/v1/chat/completions"}:
                self._send(404, {"error": {"message": "Not found", "type": "not_found"}})
                return
            try:
                content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                if content_type != "application/json":
                    raise ValueError("Content-Type must be application/json")
                if self.headers.get("Transfer-Encoding"):
                    raise ValueError("transfer-encoded request bodies are not supported")
                length = int(self.headers.get("Content-Length", "0"))
                if length < 2 or length > 128_000:
                    raise ValueError("request body must be between 2 bytes and 128 KB")
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise ValueError("request body must be a JSON object")
                if body.get("stream") is True:
                    raise ValueError("streaming is not supported by this experimental adapter")
                if self.path.endswith("/chat/completions"):
                    prompt = format_chat_messages(body.get("messages"))
                else:
                    prompt = body.get("prompt")
                    if not isinstance(prompt, str) or not prompt.strip():
                        raise ValueError("prompt must be non-empty text")
                temperature_raw = body.get("temperature", 0.8)
                max_tokens_raw = body.get("max_tokens", 96)
                top_k_raw = body.get("top_k", 50)
                top_p_raw = body.get("top_p", 0.95)
                repetition_raw = body.get("repetition_penalty", 1.0)
                do_sample_raw = body.get("do_sample", True)
                if not isinstance(do_sample_raw, bool):
                    raise ValueError("do_sample must be a boolean")
                seed_raw = body.get("seed")
                if seed_raw is not None and (
                    isinstance(seed_raw, bool) or not isinstance(seed_raw, int)
                    or not 0 <= seed_raw <= 2**63 - 1
                ):
                    raise ValueError("seed must be an integer between 0 and 2^63-1")
                generation_rng = (
                    torch.Generator(device=torch.device(device)).manual_seed(seed_raw)
                    if seed_raw is not None else None
                )
                numeric_values = (temperature_raw, max_tokens_raw, top_p_raw, repetition_raw)
                if any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in numeric_values):
                    raise ValueError("sampling parameters must be numeric values")
                if not isinstance(max_tokens_raw, int):
                    raise ValueError("max_tokens must be an integer")
                if top_k_raw is not None and (isinstance(top_k_raw, bool) or not isinstance(top_k_raw, int)):
                    raise ValueError("top_k must be null or an integer")
                temperature = float(temperature_raw)
                max_tokens = max_tokens_raw
                top_k = top_k_raw
                top_p = float(top_p_raw)
                repetition_penalty = float(repetition_raw)
                if not 0 < temperature <= 2:
                    raise ValueError("temperature must be greater than 0 and at most 2")
                if not 1 <= max_tokens <= 512:
                    raise ValueError("max_tokens must be between 1 and 512")
                if top_k is not None and top_k < 1:
                    raise ValueError("top_k must be null or a positive integer")
                if not 0 < top_p <= 1:
                    raise ValueError("top_p must be greater than 0 and at most 1")
                if not math.isfinite(repetition_penalty) or repetition_penalty < 1:
                    raise ValueError("repetition_penalty must be a finite number of at least 1")
                encoded = tokenizer.encode(prompt)
                if not encoded:
                    raise ValueError("prompt did not produce any tokens")
                if len(encoded) > model.config.block_size:
                    raise ValueError(f"prompt is too long; maximum is {model.config.block_size} tokens")
                input_ids = torch.tensor(encoded, dtype=torch.long, device=device).unsqueeze(0)
                started = time.time()
                with inference_lock, torch.inference_mode():
                    generated = model.generate(
                        input_ids, max_tokens, temperature, top_k, top_p,
                        repetition_penalty, do_sample=do_sample_raw,
                        generator=generation_rng,
                    )[0].tolist()
                answer = tokenizer.decode(generated[len(encoded):])
                completion_id = "ns-" + uuid.uuid4().hex
                usage = {
                    "prompt_tokens": len(encoded),
                    "completion_tokens": len(generated) - len(encoded),
                    "total_tokens": len(generated),
                }
                if self.path.endswith("/chat/completions"):
                    result = {
                        "id": completion_id, "object": "chat.completion",
                        "created": int(time.time()), "model": model_name,
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": answer},
                                     "finish_reason": "length"}],
                        "usage": usage,
                    }
                else:
                    result = {
                        "id": completion_id, "object": "text_completion",
                        "created": int(time.time()), "model": model_name,
                        "choices": [{"index": 0, "text": answer, "finish_reason": "length"}],
                        "usage": usage,
                    }
                result["processing_seconds"] = round(time.time() - started, 4)
                self._send(200, result)
            except (ValueError, TypeError, json.JSONDecodeError) as exc:
                self._send(400, {"error": {"message": str(exc), "type": "invalid_request_error"}})
            except Exception:
                self._send(500, {"error": {"message": "Inference failed", "type": "server_error"}})

        def log_message(self, fmt, *args):
            # Avoid logging prompts or authorization headers.
            return

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--model-name", default="new-star-foundation-experimental")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = parser.parse_args()
    api_key = os.environ.get("NEW_STAR_FOUNDATION_API_KEY", "").strip()
    if args.host not in {"127.0.0.1", "localhost", "::1"} and not api_key:
        parser.error("set NEW_STAR_FOUNDATION_API_KEY before binding to a non-loopback interface")
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    model, tokenizer = load_engine(args.checkpoint, args.device)
    server = ThreadingHTTPServer((args.host, args.port),
                                 create_handler(model, tokenizer, args.model_name, args.device, api_key))
    print(f"New Star experimental inference listening on http://{args.host}:{args.port}")
    print("This checkpoint is an untrained/experimental language-model baseline, not a production assistant.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
