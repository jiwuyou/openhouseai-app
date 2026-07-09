#!/usr/bin/env bash
set -eu

APP_ID="github-config-helper"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

log() {
  printf '[%s] %s\n' "$APP_ID" "$*"
}

warn() {
  printf '[%s] warning: %s\n' "$APP_ID" "$*" >&2
}

fail() {
  printf '[%s] error: %s\n' "$APP_ID" "$*" >&2
  exit 1
}

command -v node >/dev/null 2>&1 || fail "node is required"

if ! command -v git >/dev/null 2>&1; then
  warn "git is not installed; helper UI will show a missing-tool warning"
fi

if ! command -v gh >/dev/null 2>&1; then
  warn "gh is not installed; GitHub authorization cannot run until GitHub CLI is installed"
fi

node --check "$SCRIPT_DIR/src/github-helper.js" >/dev/null
node --check "$SCRIPT_DIR/src/server.js" >/dev/null
node --check "$SCRIPT_DIR/public/app.js" >/dev/null

if [ -f "$SCRIPT_DIR/test/github-helper.test.js" ]; then
  node --test "$SCRIPT_DIR/test/github-helper.test.js"
fi

log "check passed"
