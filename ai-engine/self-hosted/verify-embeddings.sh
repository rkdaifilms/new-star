#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || fail "Docker is required."
command -v curl >/dev/null 2>&1 || fail "curl is required."
command -v python3 >/dev/null 2>&1 || fail "python3 is required."
[[ -f .env ]] || fail "Create .env from .env.example first."
set -a
# shellcheck disable=SC1091
source .env
set +a
[[ -n "${NEW_STAR_EMBEDDING_MODEL_FILE:-}" ]] || fail "NEW_STAR_EMBEDDING_MODEL_FILE is empty."
[[ -n "${NEW_STAR_EMBEDDING_API_KEY:-}" ]] || fail "NEW_STAR_EMBEDDING_API_KEY is empty."
[[ "${#NEW_STAR_EMBEDDING_API_KEY}" -ge 32 ]] || fail "Embedding API key must be at least 32 characters."
[[ -f "models/$NEW_STAR_EMBEDDING_MODEL_FILE" ]] || fail "Embedding model file models/$NEW_STAR_EMBEDDING_MODEL_FILE is missing."
docker compose -f compose.yaml --profile embeddings config --quiet
docker compose -f compose.yaml --profile embeddings up -d new-star-embeddings
ready=0
for attempt in $(seq 1 60); do
  if curl --silent --show-error --fail http://127.0.0.1:8081/health >/dev/null 2>&1; then ready=1; break; fi
  sleep 5
done
[[ "$ready" -eq 1 ]] || { docker compose --profile embeddings logs --tail=80 new-star-embeddings; fail "Embedding model did not become healthy within five minutes."; }
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
url = "http://127.0.0.1:8081/v1/embeddings"
header = "Authorization: Bearer $NEW_STAR_EMBEDDING_API_KEY"
header = "Content-Type: application/json"
data = "{\"model\":\"local-model\",\"input\":\"New Star private search embedding verification\"}"
output = "$tmp"
EOF
curl --config "$cfg" >/dev/null
python3 - "$tmp" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    data = json.load(handle)
items = data.get("data")
if not isinstance(items, list) or not items:
    raise SystemExit("ERROR: embedding service returned no data")
vector = items[0].get("embedding")
if not isinstance(vector, list) or len(vector) != 384:
    raise SystemExit("ERROR: expected a 384-dimensional embedding; received " + str(len(vector) if isinstance(vector, list) else "no vector"))
if any(not isinstance(value, (int, float)) for value in vector):
    raise SystemExit("ERROR: embedding contains a non-numeric value")
print("PASS: private embedding service returned 384 numeric dimensions.")
PY
