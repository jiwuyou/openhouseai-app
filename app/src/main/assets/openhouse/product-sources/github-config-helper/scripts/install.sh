#!/usr/bin/env bash
set -eu

APP_ID="github-config-helper"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

log() {
  printf '[%s] %s\n' "$APP_ID" "$*"
}

fail() {
  printf '[%s] error: %s\n' "$APP_ID" "$*" >&2
  exit 1
}

command -v node >/dev/null 2>&1 || fail "node is required. Install Node.js before enabling this helper."

mkdir -p "$SCRIPT_DIR/data" "$SCRIPT_DIR/logs"
chmod 700 "$SCRIPT_DIR/data" "$SCRIPT_DIR/logs" 2>/dev/null || true
chmod +x "$SCRIPT_DIR/scripts/install.sh" "$SCRIPT_DIR/scripts/check.sh" "$SCRIPT_DIR/scripts/register-service.sh" 2>/dev/null || true

log "local install prepared at $SCRIPT_DIR"
log "no network install was performed"
