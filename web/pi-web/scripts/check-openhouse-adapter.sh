#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

[ "$(git branch --show-current)" = "openhouse" ] \
  || fail "must run on the long-lived openhouse branch"

source_dirs=(app components lib)
rg_args=(--hidden --glob '*.ts' --glob '*.tsx' --glob '*.js' --glob '*.mjs')

has_source_text() {
  local needle="$1"
  rg -l -F "${needle}" "${rg_args[@]}" "${source_dirs[@]}" >/dev/null 2>&1
}

for command_name in \
  /openhouse-first-config \
  /openhouse-docs \
  /openhouse-second-ai-handoff; do
  has_source_text "$command_name" \
    || fail "missing standard OpenHouse command in pi-web source: $command_name"
done

for forbidden in \
  '?prompt=' \
  'ControlledBrowserContract' \
  'android.intent' \
  'Intent.ACTION' \
  'EXTRA_OPENHOUSE' \
  '/root/openhouse/docs' \
  '/data/data/com.termux/files/home/openhouse/docs'; do
  if rg -n -F "$forbidden" "${rg_args[@]}" "${source_dirs[@]}" >/dev/null 2>&1; then
    fail "pi-web source contains forbidden Android/path adapter material: $forbidden"
  fi
done

for legacy_file in \
  app/api/openhouse-first-config/route.ts \
  lib/openhouse-first-config-prompt.ts \
  lib/openhouse-first-config-state.ts; do
  [ ! -f "$legacy_file" ] || fail "legacy OpenHouse first-config implementation remains: $legacy_file"
done

for forbidden_symbol in \
  'openhouseFirstConfigState' \
  'openhouseFirstConfigPrompt' \
  'OpenHouseFirstConfigResponse' \
  '/api/openhouse-first-config'; do
  if rg -n -F "$forbidden_symbol" "${rg_args[@]}" "${source_dirs[@]}" >/dev/null 2>&1; then
    fail "full built-in OpenHouse adapter symbol remains: $forbidden_symbol"
  fi
done

printf 'openhouse pi-web adapter contract passed\n'
