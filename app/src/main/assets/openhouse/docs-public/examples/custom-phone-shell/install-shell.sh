#!/usr/bin/env bash
set -eu

SHELL_ID="${SHELL_ID:-openhouse-custom-shell}"
SHELL_NAME="${SHELL_NAME:-OpenHouse Custom Shell}"
ACTIVATE_SHELL="${ACTIVATE_SHELL:-1}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPOS_ROOT="${OPENHOUSE_REPOS_DIR:-/root/smallphoneai-repos}"
SMALLPHONE_HOME="${SMALLPHONE_HOME:-$REPOS_ROOT/smallphone-home}"
SHELL_DIR="$SMALLPHONE_HOME/shells/$SHELL_ID"
SMALLPHONE_CORE_URL="${SMALLPHONE_CORE_URL:-}"
OPENHOUSE_ENDPOINTS_FILE="${OPENHOUSE_ENDPOINTS_FILE:-/data/data/com.termux/files/home/.config/openhouseai/runtime/endpoints.json}"

log() {
  printf '[custom-shell] %s\n' "$*"
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    printf 'missing command: %s\n' "$1" >&2
    exit 1
  }
}

need_cmd curl
need_cmd python3
need_cmd jq

if [ -z "$SMALLPHONE_CORE_URL" ]; then
  SMALLPHONE_CORE_URL="$(jq -er '.endpoints[] | select(.serviceId == "smallphone-core" and .name == "api") | .url' "$OPENHOUSE_ENDPOINTS_FILE")"
fi
SMALLPHONE_CORE_URL="${SMALLPHONE_CORE_URL%/}"

if ! curl -fsS --max-time 2 "$SMALLPHONE_CORE_URL/api/health" >/dev/null; then
  printf 'smallphone-core is not reachable at %s\n' "$SMALLPHONE_CORE_URL" >&2
  exit 1
fi

log "installing shell files to $SHELL_DIR"
mkdir -p "$SHELL_DIR"
cp "$SCRIPT_DIR/index.html" "$SHELL_DIR/index.html"
cp "$SCRIPT_DIR/styles.css" "$SHELL_DIR/styles.css"
cp "$SCRIPT_DIR/main.js" "$SHELL_DIR/main.js"
cp "$SCRIPT_DIR/smallphone.shell.json" "$SHELL_DIR/smallphone.shell.json"

PATCH_FILE="$(mktemp "${TMPDIR:-/tmp}/openhouse-custom-shell.XXXXXX.json")"
cleanup() {
  rm -f "$PATCH_FILE"
}
trap cleanup EXIT INT TERM

python3 - "$PATCH_FILE" "$SHELL_ID" "$SHELL_NAME" "$ACTIVATE_SHELL" <<'PY'
import json
import sys
from datetime import datetime, timezone

patch_file = sys.argv[1]
shell_id = sys.argv[2]
shell_name = sys.argv[3]
activate = sys.argv[4] not in ("0", "false", "False", "no", "NO")
now = datetime.now(timezone.utc).isoformat()

payload = {
    "shells": [
        {
            "id": shell_id,
            "name": shell_name,
            "source": "user",
            "entry": "index.html",
            "updatedAt": now,
        }
    ],
}
if activate:
    payload["activeShell"] = shell_id

with open(patch_file, "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False, indent=2)
    f.write("\n")
PY

log "registering shell through smallphone-core"
curl -fsS \
  -H "Content-Type: application/json" \
  -X PUT \
  --data-binary "@$PATCH_FILE" \
  "$SMALLPHONE_CORE_URL/api/user-content" >/dev/null

log "registered $SHELL_NAME"
log "url: $SMALLPHONE_CORE_URL/shells/$SHELL_ID/"
