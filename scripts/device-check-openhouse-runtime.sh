#!/usr/bin/env bash
set -euo pipefail

TERMUX_BASH="/data/data/com.termux/files/usr/bin/bash"

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/device-check-openhouse-runtime.sh <adb-device-id>

Checks first-install results on the device:
  - install and service-manager logs
  - service-manager health and service list
  - ports 20087, 30141, 25808
  - zero-byte service/component specs
EOF
}

log() {
  printf '[device-check-runtime] %s\n' "$*"
}

die() {
  log "ERROR: $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

device="${1:-}"
if [ "$device" = "-h" ] || [ "$device" = "--help" ]; then
  usage
  exit 0
fi
if [ -z "$device" ]; then
  usage
  exit 2
fi

need_cmd adb

adb_cmd() {
  adb -s "$device" "$@"
}

if ! adb_cmd get-state >/dev/null 2>&1; then
  die "adb device is not available: $device"
fi

log "checking $device"
adb_cmd shell run-as com.termux "$TERMUX_BASH" -s <<'REMOTE'
set -euo pipefail

export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
if [ -d "/data/data/com.termux/files/home" ]; then
  export HOME="/data/data/com.termux/files/home"
else
  export HOME="${HOME:-/data/data/com.termux/files/home}"
fi
export PATH="$HOME/.npm-global/bin:$PREFIX/bin:/system/bin:${PATH:-}"
export LD_LIBRARY_PATH="$PREFIX/lib:${LD_LIBRARY_PATH:-}"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"

errors=0

log() {
  printf '[device-check-runtime] %s\n' "$*"
}

fail() {
  errors=$((errors + 1))
  log "FAIL: $*"
}

pass() {
  log "OK: $*"
}

require_curl() {
  if ! command -v curl >/dev/null 2>&1; then
    fail "curl is missing"
    return 1
  fi
  return 0
}

resolve_service_manager_token() {
  local config token sm_bin
  token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  if [ -n "$token" ]; then
    printf '%s\n' "$token"
    return 0
  fi

  for config in \
    "$HOME/.config/openhouseai/service-manager/config.json" \
    "$HOME/.config/service-manager/config.json"; do
    [ -f "$config" ] || continue
    token="$(sed -n 's/.*"auth_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$config" | head -n 1 || true)"
    if [ -n "$token" ]; then
      printf '%s\n' "$token"
      return 0
    fi
  done

  for sm_bin in \
    "$(command -v service-manager 2>/dev/null || true)" \
    "$PREFIX/bin/service-manager" \
    "$HOME/.local/bin/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/target/release/service-manager"; do
    [ -n "$sm_bin" ] && [ -x "$sm_bin" ] || continue
    "$sm_bin" token show --config "$HOME/.config/openhouseai/service-manager/config.json" 2>/dev/null | head -n 1 | tr -d '\r\n' || true
    return 0
  done
}

service_manager_get() {
  local path="$1"
  local token
  token="$(resolve_service_manager_token || true)"
  if [ -n "$token" ]; then
    curl -q -fsSL --max-time 8 -H "Authorization: Bearer $token" "http://127.0.0.1:20087$path"
  else
    curl -q -fsSL --max-time 8 "http://127.0.0.1:20087$path"
  fi
}

check_url() {
  local label="$1"
  local url="$2"
  if ! require_curl; then
    return 0
  fi
  if curl -fsSL --max-time 8 -o /dev/null "$url"; then
    pass "$label reachable: $url"
  else
    fail "$label is not reachable: $url"
  fi
}

print_file_tail() {
  local label="$1"
  local path="$2"
  local lines="${3:-80}"
  if [ -f "$path" ]; then
    log "$label: $path"
    tail -n "$lines" "$path" 2>/dev/null || true
  else
    log "$label missing: $path"
  fi
}

check_manifest_done_marker() {
  local path="$HOME/.maintainer-logs/manifest_full.log"
  local marker code

  if [ ! -f "$path" ]; then
    fail "manifest_full log is missing: $path"
    return 0
  fi
  pass "manifest_full log exists"

  marker="$(grep '__TERMUX_MAINT_DONE__:manifest_full:' "$path" | tail -n 1 || true)"
  if [ -z "$marker" ]; then
    fail "manifest_full log has no done marker"
    return 0
  fi

  code="${marker##*:}"
  if [ "$code" = "0" ]; then
    pass "manifest_full done marker succeeded: $marker"
  else
    fail "manifest_full done marker failed: $marker"
    return 0
  fi
}

find_zero_byte_specs() {
  local dir base ubuntu_root

  for dir in \
    "$HOME/.config/openhouseai" \
    "$HOME/.config/service-manager" \
    "$HOME/.local/share/openhouseai"; do
    [ -d "$dir" ] || continue
    find "$dir" -type f -name '*.json' -size 0 -print 2>/dev/null || true
  done

  for ubuntu_root in \
    "$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu/rootfs" \
    "$PREFIX/var/lib/proot-distro/containers/ubuntu/rootfs"; do
    [ -d "$ubuntu_root" ] || continue

    for base in "$ubuntu_root/root" "$ubuntu_root/home"/*; do
      [ -d "$base" ] || continue
      for dir in \
        "$base/.config/openhouseai" \
        "$base/.config/service-manager" \
        "$base/.local/share/openhouseai"; do
        [ -d "$dir" ] || continue
        find "$dir" -type f -name '*.json' -size 0 -print 2>/dev/null || true
      done
    done
  done
}

check_zero_byte_specs() {
  local found
  found="$(find_zero_byte_specs)"

  if [ -n "$found" ]; then
    fail "zero-byte JSON specs found"
    printf '%s\n' "$found"
  else
    pass "no zero-byte JSON specs found"
  fi
}

validate_service_list_ids_fallback() {
  local missing=0
  local service_id item compact header

  log "python3 is missing; using conservative per-service endpoint fallback"
  for service_id in "$@"; do
    if ! item="$(service_manager_get "/api/v1/services/$service_id" 2>/dev/null)"; then
      printf 'MISSING\t%s\t%s\n' "$service_id" "GET /api/v1/services/$service_id failed"
      missing=1
      continue
    fi

    compact="$(printf '%s' "$item" | tr -d '[:space:]')"
    header="${compact%%\"spec\":*}"
    header="${header%%\"command\":*}"
    header="${header%%\"description\":*}"

    case "$header" in
      *"\"id\":\"$service_id\""*)
        printf 'OK\t%s\t%s\n' "$service_id" "top-level-ish id via /api/v1/services/$service_id"
        ;;
      *)
        printf 'MISSING\t%s\t%s\n' "$service_id" "endpoint returned, but no safe top-level id match before nested spec/command/description"
        missing=1
        ;;
    esac
  done

  return "$missing"
}

validate_service_list_ids() {
  local services_file="$1"
  shift

  if command -v python3 >/dev/null 2>&1; then
    python3 - "$services_file" "$@" <<'PY'
import json
import sys

services_path = sys.argv[1]
expected = sys.argv[2:]

with open(services_path, "r", encoding="utf-8") as f:
    data = json.load(f)


def service_items(value):
    if isinstance(value, list):
        return [(None, item) for item in value if isinstance(item, dict)]
    if not isinstance(value, dict):
        return []

    items = []
    if "id" in value or "name" in value:
        items.append((None, value))

    for key in ("services", "items", "data", "results"):
        nested = value.get(key)
        if isinstance(nested, list):
            items.extend((None, item) for item in nested if isinstance(item, dict))
        elif isinstance(nested, dict):
            items.extend(
                (string_value(item_key), item)
                for item_key, item in nested.items()
                if isinstance(item, dict)
            )

    return items


def string_value(value):
    if isinstance(value, str):
        return value
    if isinstance(value, (int, float)):
        return str(value)
    return ""


items = service_items(data)
ids = set()

for item_key, item in items:
    if item_key:
        ids.add(item_key)

    sid = string_value(item.get("id"))
    if sid:
        ids.add(sid)

missing = []
for service_id in expected:
    if service_id in ids:
        print(f"OK\t{service_id}\ttop-level id")
    else:
        missing.append(service_id)
        print(f"MISSING\t{service_id}\tno matching top-level service id")

print("FOUND_IDS\t" + ",".join(sorted(ids)))

if missing:
    sys.exit(1)
PY
    return $?
  fi

  validate_service_list_ids_fallback "$@"
}

check_service_list() {
  local body=""
  local services_file=""
  local validation_output=""
  if ! require_curl; then
    return 0
  fi
  if ! body="$(service_manager_get /api/v1/services 2>/dev/null)"; then
    fail "service-manager service list is not available"
    return 0
  fi

  log "service-manager services:"
  printf '%s\n' "$body"

  services_file="$TMPDIR/openhouse-services-$$.json"
  printf '%s' "$body" > "$services_file"
  if validation_output="$(validate_service_list_ids "$services_file" pi-agent pi-web aionui-web 2>&1)"; then
    pass "service-manager has stable top-level service ids: pi-agent, pi-web, aionui-web"
  else
    fail "service-manager stable service id validation failed"
  fi
  printf '%s\n' "$validation_output"
  rm -f "$services_file" >/dev/null 2>&1 || true
}

status_state() {
  if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))' 2>/dev/null || true
  else
    sed -n 's/.*"state"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
  fi
}

check_service_statuses() {
  local service_id body state
  for service_id in pi-agent pi-web aionui-web; do
    if ! body="$(service_manager_get "/api/v1/services/$service_id/status" 2>/dev/null)"; then
      fail "service-manager status is not available for $service_id"
      continue
    fi
    log "service-manager status $service_id: $body"
    state="$(printf '%s' "$body" | status_state)"
    if [ "$state" = "running" ]; then
      pass "service-manager reports $service_id running"
    else
      fail "service-manager reports $service_id state=$state"
    fi
  done
}

check_global_commands() {
  local command_name
  for command_name in pi-web wuyou; do
    if command -v "$command_name" >/dev/null 2>&1 && "$command_name" --help >/dev/null 2>&1; then
      pass "global command is available: $command_name"
    else
      fail "global command is missing or unhealthy: $command_name"
    fi
  done
}

log "identity: $(id 2>/dev/null || true)"
log "HOME=$HOME"
log "PREFIX=$PREFIX"
mkdir -p "$TMPDIR"

check_manifest_done_marker
check_zero_byte_specs
check_url "service-manager health" "http://127.0.0.1:20087/api/v1/health"
check_service_list
check_service_statuses
check_global_commands
check_url "pi-web" "http://127.0.0.1:30141/"
check_url "AionUi" "http://127.0.0.1:25808/"

print_file_tail "manifest_full log tail" "$HOME/.maintainer-logs/manifest_full.log" 120
print_file_tail "service-manager log tail" "$HOME/.smallphoneai/logs/service-manager.log" 120

if [ "$errors" -ne 0 ]; then
  log "runtime check failed with $errors error(s)"
  exit 1
fi

log "runtime check passed"
REMOTE

log "done"
