#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf 'CHECK: %s\n' "$*"; }

command -v docker >/dev/null 2>&1 || fail "Docker is not installed."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required."
command -v curl >/dev/null 2>&1 || fail "curl is required on the GPU host."
command -v python3 >/dev/null 2>&1 || fail "python3 is required for response validation."
command -v nvidia-smi >/dev/null 2>&1 || fail "NVIDIA drivers are not installed or nvidia-smi is unavailable."
docker info >/dev/null 2>&1 || fail "Docker daemon is not reachable."
docker info --format '{{json .Runtimes}}' | grep -q nvidia || fail "NVIDIA Container Toolkit runtime is not visible to Docker."

[[ -f .env ]] || fail "Create .env from .env.example first."
set -a
# shellcheck disable=SC1091
source .env
set +a

[[ -n "${NEW_STAR_MODEL_FILE:-}" ]] || fail "NEW_STAR_MODEL_FILE is empty."
[[ -n "${NEW_STAR_MODEL_API_KEY:-}" ]] || fail "NEW_STAR_MODEL_API_KEY is empty."
[[ "$NEW_STAR_MODEL_API_KEY" =~ ^[A-Za-z0-9_-]{32,}$ ]] || fail "NEW_STAR_MODEL_API_KEY must be at least 32 URL-safe random characters."
[[ "$NEW_STAR_MODEL_API_KEY" != replace-* ]] || fail "Replace the example model API key before starting the service."
[[ -f "models/$NEW_STAR_MODEL_FILE" ]] || fail "Model file models/$NEW_STAR_MODEL_FILE is missing."

note "Checking Compose configuration."
docker compose -f compose.yaml config --quiet
note "Checking GPU visibility."
nvidia-smi -L
note "Starting private model service."
docker compose -f compose.yaml up -d
note "Waiting for the model health endpoint."
ready=0
for attempt in $(seq 1 60); do
  if curl --silent --show-error --fail http://127.0.0.1:8080/health >/dev/null 2>&1; then ready=1; break; fi
  sleep 5
done
[[ "$ready" -eq 1 ]] || { docker compose -f compose.yaml logs --tail=80 new-star-model; fail "Model did not become healthy within five minutes."; }

tmp="$(mktemp)"
cfg="$(mktemp)"
cleanup() { rm -f "$tmp" "$cfg"; }
trap cleanup EXIT
chmod 600 "$cfg"
cat > "$cfg" <<EOF
silent
show-error
fail
request = "POST"
url = "http://127.0.0.1:8080/v1/chat/completions"
header = "Authorization: Bearer $NEW_STAR_MODEL_API_KEY"
header = "Content-Type: application/json"
data = "{\"model\":\"local-model\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with exactly NEW_STAR_PRIVATE_MODEL_READY\"}],\"temperature\":0,\"max_tokens\":40}"
output = "$tmp"
EOF
note "Running authenticated inference smoke test."
curl --config "$cfg" >/dev/null
python3 - "$tmp" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    data = json.load(handle)
choices = data.get("choices")
if not isinstance(choices, list) or not choices:
    raise SystemExit("ERROR: model returned no completion choices")
message = choices[0].get("message", {})
content = message.get("content", "")
if not isinstance(content, str) or "NEW_STAR_PRIVATE_MODEL_READY" not in content:
    raise SystemExit("ERROR: model did not return the requested readiness marker; received an unexpected response")
print("PASS: authenticated private model inference returned the readiness marker.")
PY
note "Private model is ready. Configure the Supabase secrets documented in README.md before enabling self-hosted mode."
