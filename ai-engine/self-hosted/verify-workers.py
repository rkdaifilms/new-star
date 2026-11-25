#!/usr/bin/env python3
"""Authenticated health and inference checks for New Star private media workers.

Run health/config checks with:
  python3 verify-workers.py
Run real image, vision, speech, and search inference with:
  python3 verify-workers.py --infer
Run a real video job separately (GPU-intensive):
  python3 verify-workers.py --video
All worker URLs must be HTTPS reverse-proxy URLs. Secrets are never printed.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
from typing import Any

WORKERS = {
    "image": ("NEW_STAR_IMAGE_BASE_URL", "NEW_STAR_IMAGE_API_KEY", 8091),
    "video": ("NEW_STAR_VIDEO_BASE_URL", "NEW_STAR_VIDEO_API_KEY", 8092),
    "speech": ("NEW_STAR_SPEECH_BASE_URL", "NEW_STAR_SPEECH_API_KEY", 8090),
    "search": ("NEW_STAR_SEARCH_BASE_URL", "NEW_STAR_SEARCH_API_KEY", 8093),
    "vision": ("NEW_STAR_VISION_BASE_URL", "NEW_STAR_VISION_API_KEY", 8094),
}
PNG_1X1 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4WQAAAAASUVORK5CYII="
)
failures: list[str] = []
passed = 0
skipped = 0


def report(status: str, name: str, detail: str) -> None:
    global passed, skipped
    print(f"{status}: {name}: {detail}")
    if status == "FAIL":
        failures.append(name)
    elif status == "PASS":
        passed += 1
    else:
        skipped += 1


def request_json(url: str, key: str | None = None, body: dict[str, Any] | None = None,
                 timeout: int = 30) -> tuple[int, dict[str, Any], str]:
    headers = {"Accept": "application/json"}
    data = None
    if key is not None:
        headers["Authorization"] = "Bearer " + key
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read(14 * 1024 * 1024 + 1)
            if len(raw) > 14 * 1024 * 1024:
                raise ValueError("response exceeded the 14 MiB diagnostic limit")
            try:
                payload = json.loads(raw.decode("utf-8")) if raw else {}
            except (UnicodeDecodeError, json.JSONDecodeError):
                payload = {}
            return response.status, payload if isinstance(payload, dict) else {}, raw.decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        raw = error.read(1024 * 1024).decode("utf-8", "replace")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = {}
        return error.code, payload if isinstance(payload, dict) else {}, raw
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise RuntimeError(type(error).__name__ + " while contacting worker") from None


def validate_config(name: str, base: str, key: str) -> str:
    if not base or not key:
        raise ValueError("endpoint URL or API key is not set")
    if len(key) < 32 or key.startswith("replace-") or "YOUR" in key:
        raise ValueError("API key must be a real, unique value of at least 32 characters")
    if not base.startswith("https://") or "@" in base:
        raise ValueError("endpoint must be an HTTPS URL without embedded credentials")
    return base.rstrip("/")


def health_check(name: str, base: str, key: str) -> tuple[str, str] | None:
    try:
        endpoint = validate_config(name, base, key)
        status, data, _ = request_json(endpoint + "/health", timeout=15)
        if status != 200 or data.get("ok") is not True:
            raise RuntimeError("health endpoint did not return HTTP 200 with ok=true")
        if name in ("image", "video", "vision") and data.get("cuda") is not True:
            raise RuntimeError("worker is reachable but reports CUDA unavailable")
        report("PASS", name, "HTTPS health endpoint is responding; secret not displayed")
        return endpoint, key
    except (ValueError, RuntimeError) as error:
        report("FAIL", name, str(error))
        return None


def infer_image(base: str, key: str) -> None:
    status, data, _ = request_json(base + "/v1/images/generations", key, {
        "prompt": "A simple blue geometric star on a clean white background",
        "aspect_ratio": "1:1", "image_size": "1K",
    }, timeout=180)
    encoded = data.get("image")
    if status != 200 or not isinstance(encoded, str):
        raise RuntimeError("image generation did not return HTTP 200 with image data")
    raw = base64.b64decode(encoded, validate=True)
    if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("image worker did not return a PNG")
    report("PASS", "image-inference", f"generated PNG ({len(raw)} bytes)")


def infer_vision(base: str, key: str) -> None:
    status, data, _ = request_json(base + "/v1/vision/analyze", key, {
        "image": PNG_1X1, "mime_type": "image/png",
        "prompt": "Describe the visible image briefly.",
    }, timeout=180)
    if status != 200 or not isinstance(data.get("answer"), str) or not data["answer"].strip():
        raise RuntimeError("vision worker did not return a non-empty answer")
    report("PASS", "vision-inference", "image analysis returned a non-empty answer")


def infer_speech(base: str, key: str) -> None:
    language = os.getenv("NEW_STAR_SMOKE_SPEECH_LANGUAGE", "en")
    status, data, _ = request_json(base + "/v1/audio/speech", key, {
        "input": "New Star private speech test.", "language": language,
    }, timeout=120)
    encoded = data.get("audio")
    if status != 200 or not isinstance(encoded, str):
        raise RuntimeError("speech worker did not return audio; verify the configured Piper voice")
    audio = base64.b64decode(encoded, validate=True)
    if not audio.startswith(b"RIFF") or len(audio) < 44:
        raise RuntimeError("speech worker did not return a valid WAV file")
    report("PASS", "speech-tts", f"generated WAV ({len(audio)} bytes) for language {language}")


def infer_search(base: str, key: str) -> None:
    status, data, _ = request_json(base + "/v1/search", key, {
        "query": os.getenv("NEW_STAR_SMOKE_SEARCH_QUERY", "New Star"),
        "limit": 5,
    }, timeout=40)
    if status != 200 or not isinstance(data.get("results"), list):
        raise RuntimeError("private search did not return a results array")
    report("PASS", "search-inference", f"query completed with {len(data['results'])} indexed result(s)")


def infer_crawl(base: str, key: str) -> None:
    url = os.getenv("NEW_STAR_SMOKE_CRAWL_URL", "").strip()
    if not url:
        report("SKIP", "search-crawl", "set NEW_STAR_SMOKE_CRAWL_URL to a public page to test crawling")
        return
    status, data, _ = request_json(base + "/v1/crawl", key, {"url": url}, timeout=90)
    if status != 200 or not isinstance(data.get("pages_indexed"), int) or data["pages_indexed"] < 1:
        raise RuntimeError("crawler did not index at least one page")
    report("PASS", "search-crawl", f"indexed {data['pages_indexed']} page(s)")


def infer_video(base: str, key: str) -> None:
    status, data, _ = request_json(base + "/v1/videos/generations", key, {
        "prompt": "A calm cinematic view of stars moving slowly across a night sky",
        "aspect_ratio": "16:9", "resolution": "480p", "duration": "6s",
    }, timeout=30)
    job_id = data.get("job_id")
    if status != 202 or not isinstance(job_id, str):
        raise RuntimeError("video worker did not accept a generation job")
    deadline = time.monotonic() + int(os.getenv("NEW_STAR_SMOKE_VIDEO_TIMEOUT", "1800"))
    while time.monotonic() < deadline:
        code, job, _ = request_json(base + "/v1/videos/jobs/" + job_id, key, timeout=20)
        if code != 200:
            raise RuntimeError("video job status endpoint returned an error")
        state = job.get("status")
        if state == "failed":
            raise RuntimeError("video model job failed; inspect private worker logs on the GPU host")
        if state == "completed":
            out = urllib.request.Request(base + "/v1/videos/jobs/" + job_id + "/output",
                headers={"Authorization": "Bearer " + key})
            with urllib.request.urlopen(out, timeout=120) as response:
                payload = response.read(50 * 1024 * 1024 + 1)
                if response.headers.get_content_type() != "video/mp4" or not payload.startswith(b"\x00\x00\x00"):
                    raise RuntimeError("video worker returned an invalid MP4 response")
                if len(payload) > 50 * 1024 * 1024:
                    raise RuntimeError("video output exceeded 50 MiB")
            report("PASS", "video-inference", f"completed MP4 ({len(payload)} bytes)")
            return
        if state not in ("queued", "in_progress"):
            raise RuntimeError("video worker returned an unknown job state")
        time.sleep(10)
    raise RuntimeError("video generation exceeded the configured smoke-test timeout")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--infer", action="store_true", help="run real image, vision, speech, and search inference")
    parser.add_argument("--video", action="store_true", help="submit and wait for an expensive real video-generation job")
    parser.add_argument("--require-all", action="store_true", help="fail if any of the five worker endpoints is not configured")
    args = parser.parse_args()

    ready: dict[str, tuple[str, str]] = {}
    for name, (url_name, key_name, _port) in WORKERS.items():
        url, key = os.getenv(url_name, "").strip(), os.getenv(key_name, "").strip()
        if not url and not key:
            if args.require_all:
                report("FAIL", name, f"{url_name} and {key_name} are required")
            else:
                report("SKIP", name, f"{url_name} and {key_name} are not configured")
            continue
        if not url or not key:
            report("FAIL", name, f"both {url_name} and {key_name} must be set")
            continue
        result = health_check(name, url, key)
        if result:
            ready[name] = result

    if args.infer:
        tests = {
            "image": infer_image, "vision": infer_vision,
            "speech": infer_speech, "search": infer_search,
        }
        for name, test in tests.items():
            if name not in ready:
                report("SKIP", name + "-inference", "worker health/configuration did not pass")
                continue
            try:
                test(*ready[name])
            except Exception as error:
                report("FAIL", name + "-inference", str(error))
        if "search" in ready:
            try:
                infer_crawl(*ready["search"])
            except Exception as error:
                report("FAIL", "search-crawl", str(error))

    if args.video:
        if "video" not in ready:
            report("SKIP", "video-inference", "video worker health/configuration did not pass")
        else:
            try:
                infer_video(*ready["video"])
            except Exception as error:
                report("FAIL", "video-inference", str(error))

    print(f"\nRESULT: {passed} passed, {skipped} skipped, {len(failures)} failed.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
