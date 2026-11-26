#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf 'NEW STAR DEPLOY: %s\n' "$*"; }

command -v docker >/dev/null 2>&1 || fail "Docker is required."
command -v curl >/dev/null 2>&1 || fail "curl is required."
command -v python3 >/dev/null 2>&1 || fail "Python 3 is required."
command -v nvidia-smi >/dev/null 2>&1 || fail "NVIDIA driver tools are required for the configured GPU workers."
docker info >/dev/null 2>&1 || fail "Docker daemon is unavailable."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required."
docker info --format '{{json .Runtimes}}' | grep -q nvidia || fail "NVIDIA Container Toolkit is not available to Docker."
[[ -f .env ]] || fail "Create ai-engine/self-hosted/.env from .env.example and configure real secrets first."

set -a
# shellcheck disable=SC1091
source .env
set +a

python3 - <<'PY'
import json
import os
import pathlib
import sys

required = [
    "NEW_STAR_MODEL_API_KEY",
    "NEW_STAR_IMAGE_API_KEY",
    "NEW_STAR_VIDEO_API_KEY",
    "NEW_STAR_SPEECH_API_KEY",
    "NEW_STAR_SEARCH_API_KEY",
    "NEW_STAR_VISION_API_KEY",
]
values = {}
for name in required:
    value = os.environ.get(name, "")
    if len(value) < 32 or value.startswith("replace-") or "YOUR" in value:
        sys.exit(f"ERROR: {name} must be a unique random secret with at least 32 characters.")
    values[name] = value
if len(set(values.values())) != len(values):
    sys.exit("ERROR: each New Star worker must use a different API key.")
model = os.environ.get("NEW_STAR_MODEL_FILE", "")
if not model or not pathlib.Path("models", model).is_file():
    sys.exit("ERROR: NEW_STAR_MODEL_FILE must name an existing GGUF file in ai-engine/self-hosted/models/.")
voices_raw = os.environ.get("NEW_STAR_PIPER_VOICES", "")
try:
    voices = json.loads(voices_raw)
except json.JSONDecodeError:
    sys.exit("ERROR: NEW_STAR_PIPER_VOICES must be a valid JSON object.")
if not isinstance(voices, dict) or not voices:
    sys.exit("ERROR: configure at least one licensed Piper voice in NEW_STAR_PIPER_VOICES.")
for language, filename in voices.items():
    if not isinstance(language, str) or not isinstance(filename, str) or not filename.endswith(".onnx"):
        sys.exit("ERROR: Piper voices must map language codes to .onnx filenames.")
    if not pathlib.Path("models", filename).is_file():
        sys.exit(f"ERROR: configured Piper voice file is missing: models/{filename}")
    if not pathlib.Path("models", filename + ".json").is_file():
        sys.exit(f"ERROR: matching Piper voice config is missing: models/{filename}.json")
print("PASS: required secrets, local chat model, and configured Piper voice files validated.")
PY

note "Validating every Compose profile."
docker compose -f compose.yaml --profile speech --profile image --profile video --profile search --profile vision config --quiet
note "Building and starting chat plus all private workers."
docker compose -f compose.yaml --profile speech --profile image --profile video --profile search --profile vision up -d --build

check_worker() {
  local name="$1" port="$2" path="$3"
  local health="http://127.0.0.1:${port}/health"
  local auth_body
  case "$name" in
    new-star-speech) auth_body='{"input":"authorization probe","language":"en"}' ;;
    new-star-image) auth_body='{"prompt":"authorization probe","aspect_ratio":"1:1","image_size":"1K"}' ;;
    new-star-video) auth_body='{"prompt":"authorization probe","aspect_ratio":"16:9","resolution":"480p","duration":"6s"}' ;;
    new-star-search) auth_body='{"query":"authorization probe","limit":1}' ;;
    new-star-vision) auth_body='{"image":"iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAGUlEQVR4nGPUSNnCQApgIkn1qIZRDUNKAwAFQAFgfUuIBgAAAABFJREFUeJxjYBgFo2AUjIJRMAAAAwAB1t1oNwAAAABJRU5ErkJggg==","mime_type":"image/png","prompt":"authorization probe"}' ;;
    *) fail "No schema-valid authorization probe is configured for $name." ;;
  esac
  local ready=0
  for attempt in $(seq 1 90); do
    if curl --silent --show-error --fail --max-time 5 "$health" >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 5
  done
  if [[ "$ready" -ne 1 ]]; then
    docker compose -f compose.yaml logs --tail=60 "$name" >&2 || true
    fail "$name did not become healthy within 7.5 minutes."
  fi
  local auth_status
  auth_status="$(curl --silent --output /dev/null --write-out '%{http_code}' --max-time 10 \
    --request POST "$path" \
    --header 'Authorization: Bearer new-star-invalid-auth-probe' \
    --header 'Content-Type: application/json' \
    --data "$auth_body" || true)"
  [[ "$auth_status" == "401" ]] || fail "$name protected endpoint did not reject invalid credentials (HTTP $auth_status)."
  note "$name health passed and invalid credentials were rejected."
}

note "Checking chat model health and authenticated inference."
for attempt in $(seq 1 90); do
  if curl --silent --show-error --fail --max-time 5 http://127.0.0.1:8080/health >/dev/null 2>&1; then break; fi
  if [[ "$attempt" -eq 90 ]]; then docker compose -f compose.yaml logs --tail=80 new-star-model >&2; fail "Private chat model did not become healthy."; fi
  sleep 5
done
note "Chat model health is responding. Running the authenticated chat inference smoke test."
bash verify.sh

check_worker new-star-speech 8090 http://127.0.0.1:8090/v1/audio/speech
check_worker new-star-image 8091 http://127.0.0.1:8091/v1/images/generations
check_worker new-star-video 8092 http://127.0.0.1:8092/v1/videos/generations
check_worker new-star-search 8093 http://127.0.0.1:8093/v1/search
check_worker new-star-vision 8094 http://127.0.0.1:8094/v1/vision/analyze

note "Authenticated private chat inference passed; all five optional media/search worker containers are healthy and reject invalid credentials."
note "This is not a generation test. Run python3 verify-workers.py --infer and python3 verify-workers.py --video after configuring HTTPS reverse-proxy URLs and Supabase secrets."
note "All ports remain bound to loopback by Compose. Do not publish worker ports directly to the internet."
