#!/usr/bin/env bash
set -Eeuo pipefail
set +x

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
note() { printf 'NEW STAR SECRETS: %s\n' "$*"; }

command -v supabase >/dev/null 2>&1 || fail "Supabase CLI is required."
command -v python3 >/dev/null 2>&1 || fail "Python 3 is required."
[[ -f .env ]] || fail "Create .env from .env.example and configure real deployment values."
[[ -n "${SUPABASE_PROJECT_REF:-}" ]] || fail "Set SUPABASE_PROJECT_REF to the target Supabase project ref."

set -a
# shellcheck disable=SC1091
source .env
set +a

python3 - <<'PY'
import os, re, sys
required = [
    "NEW_STAR_MODEL_BASE_URL", "NEW_STAR_MODEL_NAME", "NEW_STAR_MODEL_API_KEY",
    "NEW_STAR_IMAGE_BASE_URL", "NEW_STAR_IMAGE_API_KEY",
    "NEW_STAR_VIDEO_BASE_URL", "NEW_STAR_VIDEO_API_KEY",
    "NEW_STAR_SPEECH_BASE_URL", "NEW_STAR_SPEECH_API_KEY",
    "NEW_STAR_SEARCH_BASE_URL", "NEW_STAR_SEARCH_API_KEY",
    "NEW_STAR_VISION_BASE_URL", "NEW_STAR_VISION_API_KEY",
]
optional = ["NEW_STAR_EMBEDDING_BASE_URL", "NEW_STAR_EMBEDDING_API_KEY"]
for name in required:
    value = os.environ.get(name, "").strip()
    if not value or "YOUR_PRIVATE_HOST" in value or "replace-" in value or "YOUR_" in value:
        sys.exit(f"ERROR: {name} is missing or still contains an example placeholder.")
for name in required + optional:
    value = os.environ.get(name, "").strip()
    if name.endswith("_API_KEY") and value and (len(value) < 32 or "replace-" in value):
        sys.exit(f"ERROR: {name} must be a real random secret of at least 32 characters.")
worker_keys = [os.environ.get(name, "").strip() for name in [
    "NEW_STAR_MODEL_API_KEY", "NEW_STAR_IMAGE_API_KEY", "NEW_STAR_VIDEO_API_KEY",
    "NEW_STAR_SPEECH_API_KEY", "NEW_STAR_SEARCH_API_KEY", "NEW_STAR_VISION_API_KEY",
]]
if len(set(worker_keys)) != len(worker_keys):
    sys.exit("ERROR: each private worker must use a different API key.")
if os.environ.get("NEW_STAR_EMBEDDING_API_KEY", "").strip() in worker_keys:
    sys.exit("ERROR: the embedding worker must use its own separate API key.")
for name in [x for x in required + optional if x.endswith("_BASE_URL")]:
    value = os.environ.get(name, "").strip()
    if not value: continue
    from urllib.parse import urlparse
    u = urlparse(value)
    if u.scheme != "https" or not u.hostname or u.username or u.password or u.query or u.fragment:
        sys.exit(f"ERROR: {name} must be an HTTPS URL without credentials, query, or fragment.")
    if any(x in u.hostname.lower() for x in ("your_", "example.")):
        sys.exit(f"ERROR: {name} still points to an example host.")
if not re.fullmatch(r"[a-z0-9]{20}", os.environ["SUPABASE_PROJECT_REF"]):
    sys.exit("ERROR: SUPABASE_PROJECT_REF must be the 20-character Supabase project ref.")
print("PASS: required private endpoint URLs, model name, project ref, and API key formats validated.")
PY

ARGS=(
  "NEW_STAR_AI_MODE=self_hosted"
  "NEW_STAR_MODEL_BASE_URL=$NEW_STAR_MODEL_BASE_URL"
  "NEW_STAR_MODEL_NAME=$NEW_STAR_MODEL_NAME"
  "NEW_STAR_MODEL_API_KEY=$NEW_STAR_MODEL_API_KEY"
  "NEW_STAR_IMAGE_BASE_URL=$NEW_STAR_IMAGE_BASE_URL"
  "NEW_STAR_IMAGE_API_KEY=$NEW_STAR_IMAGE_API_KEY"
  "NEW_STAR_VIDEO_BASE_URL=$NEW_STAR_VIDEO_BASE_URL"
  "NEW_STAR_VIDEO_API_KEY=$NEW_STAR_VIDEO_API_KEY"
  "NEW_STAR_SPEECH_BASE_URL=$NEW_STAR_SPEECH_BASE_URL"
  "NEW_STAR_SPEECH_API_KEY=$NEW_STAR_SPEECH_API_KEY"
  "NEW_STAR_SEARCH_BASE_URL=$NEW_STAR_SEARCH_BASE_URL"
  "NEW_STAR_SEARCH_API_KEY=$NEW_STAR_SEARCH_API_KEY"
  "NEW_STAR_VISION_BASE_URL=$NEW_STAR_VISION_BASE_URL"
  "NEW_STAR_VISION_API_KEY=$NEW_STAR_VISION_API_KEY"
)
if [[ -n "${NEW_STAR_EMBEDDING_BASE_URL:-}" || -n "${NEW_STAR_EMBEDDING_API_KEY:-}" ]]; then
  [[ -n "${NEW_STAR_EMBEDDING_BASE_URL:-}" && -n "${NEW_STAR_EMBEDDING_API_KEY:-}" ]] || fail "Configure both embedding URL and key, or neither."
  ARGS+=("NEW_STAR_EMBEDDING_BASE_URL=$NEW_STAR_EMBEDDING_BASE_URL" "NEW_STAR_EMBEDDING_API_KEY=$NEW_STAR_EMBEDDING_API_KEY")
fi

note "Uploading private-mode gateway secrets to Supabase project $SUPABASE_PROJECT_REF."
supabase secrets set --project-ref "$SUPABASE_PROJECT_REF" "${ARGS[@]}"
note "Supabase accepted the secrets. This does not prove the private endpoints are reachable or inference works."
note "Keep NEW_STAR_AI_MODE=self_hosted. The gateway fails closed rather than falling back to external model providers."
