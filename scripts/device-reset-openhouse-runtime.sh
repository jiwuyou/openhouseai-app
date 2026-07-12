#!/usr/bin/env bash
set -euo pipefail

TERMUX_BASH="/data/data/com.termux/files/usr/bin/bash"

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/device-reset-openhouse-runtime.sh <adb-device-id> [--light|--full]

--light  Stop OpenHouse managed processes and clear OpenHouse runtime state.
--full   Also remove the Ubuntu proot-distro rootfs.

The reset preserves already-synced APK assets under $HOME/.smallphoneai-bootstrap,
so the fast loop can run as:
  sync assets -> reset runtime -> run first install -> check runtime
EOF
}

log() {
  printf '[device-reset-runtime] %s\n' "$*"
}

die() {
  log "ERROR: $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

device="${1:-}"
mode="${2:---light}"
if [ "$device" = "-h" ] || [ "$device" = "--help" ]; then
  usage
  exit 0
fi
if [ -z "$device" ]; then
  usage
  exit 2
fi

case "$mode" in
  --light|--full) ;;
  *) usage; die "unknown reset mode: $mode" ;;
esac

need_cmd adb

adb_cmd() {
  adb -s "$device" "$@"
}

if ! adb_cmd get-state >/dev/null 2>&1; then
  die "adb device is not available: $device"
fi

log "resetting $device with mode $mode"
adb_cmd shell run-as com.termux "$TERMUX_BASH" -s -- "$mode" <<'REMOTE'
set -euo pipefail

mode="${1:?missing reset mode}"
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
if [ -d "/data/data/com.termux/files/home" ]; then
  export HOME="/data/data/com.termux/files/home"
else
  export HOME="${HOME:-/data/data/com.termux/files/home}"
fi
export PATH="$PREFIX/bin:/system/bin:${PATH:-}"
export LD_LIBRARY_PATH="$PREFIX/lib:${LD_LIBRARY_PATH:-}"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"

log() {
  printf '[device-reset-runtime] %s\n' "$*"
}

service_manager_url() {
  printf '%s\n' "${SERVICE_MANAGER_URL:-http://127.0.0.1:20087}"
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

service_manager_request() {
  local method="$1"
  local path="$2"
  local token url
  command -v curl >/dev/null 2>&1 || return 127
  token="$(resolve_service_manager_token || true)"
  url="$(service_manager_url)${path}"
  if [ -n "$token" ]; then
    curl -q -fsS --max-time 6 -X "$method" -H "Authorization: Bearer $token" "$url"
  else
    curl -q -fsS --max-time 6 -X "$method" "$url"
  fi
}

managed_service_ids() {
  local body
  body="$(service_manager_request GET /api/v1/services 2>/dev/null || true)"
  if [ -z "$body" ]; then
    printf '%s\n' pi-agent pi-web aionui-web cloudcli
    return 0
  fi

  if command -v python3 >/dev/null 2>&1; then
    SERVICE_MANAGER_SERVICES_JSON="$body" python3 <<'PY' || true
import json
import os

raw = os.environ.get("SERVICE_MANAGER_SERVICES_JSON", "")
stable = {"pi-agent", "pi-web", "aionui-web", "cloudcli"}
needles = ("openhouse", "smallphone", "smallphoneai", "pi-agent", "pi-web", "aionui", "cloudcli")

try:
    data = json.loads(raw)
except Exception:
    data = []

if isinstance(data, dict):
    for key in ("services", "data", "items"):
        if isinstance(data.get(key), list):
            data = data[key]
            break
    else:
        data = list(data.values()) if all(isinstance(v, dict) for v in data.values()) else []

def text_for(service):
    parts = []
    if isinstance(service, dict):
        for key in ("id", "name", "component", "component_id", "description", "provider"):
            value = service.get(key)
            if value is not None:
                parts.append(str(value))
        tags = service.get("tags")
        if isinstance(tags, list):
            parts.extend(str(tag) for tag in tags)
        spec = service.get("spec")
        if isinstance(spec, dict):
            for key in ("id", "name", "working_dir", "command", "log_file"):
                value = spec.get(key)
                if value is not None:
                    parts.append(str(value))
    return " ".join(parts).lower()

selected = []
for service in (data if isinstance(data, list) else []):
    if not isinstance(service, dict):
        continue
    service_id = str(service.get("id") or service.get("name") or "").strip()
    if not service_id:
        continue
    haystack = text_for(service)
    if service_id in stable or any(needle in haystack for needle in needles):
        selected.append(service_id)

for service_id in stable:
    if service_id not in selected:
        selected.append(service_id)

for service_id in selected:
    print(service_id)
PY
  else
    printf '%s\n' "$body" \
      | tr '{},[]' '\n' \
      | sed -n 's/.*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
      | grep -E '(^pi-agent$|^pi-web$|^aionui-web$|^cloudcli$|openhouse|smallphone|aionui)' || true
    printf '%s\n' pi-agent pi-web aionui-web cloudcli
  fi | awk 'NF && !seen[$0]++'
}

stop_managed_services_via_service_manager() {
  local ids service_id
  ids="$(managed_service_ids || true)"
  if [ -z "$ids" ]; then
    log "service-manager did not report OpenHouse managed services"
    return 0
  fi

  log "requesting service-manager stop for managed services"
  while IFS= read -r service_id; do
    [ -n "$service_id" ] || continue
    if service_manager_request POST "/api/v1/services/$service_id/stop" >/dev/null 2>&1; then
      log "stopped via service-manager: $service_id"
    else
      log "service-manager stop skipped/failed: $service_id"
    fi
  done <<EOF
$ids
EOF
}

pid_cmdline() {
  local pid="$1"
  tr '\0' ' ' <"/proc/$pid/cmdline" 2>/dev/null || true
}

is_openhouse_cmdline() {
  case "$1" in
    *openhouse-pi-agent-sentinel*|*openhouse-pi-web-start*|*openhouse-aionui-web-start*|\
    *run-manifest_full.sh*|*openhouseai-bootstrap.sh*|*.smallphoneai-bootstrap*|\
    *smallphoneai-repos*|*.local/share/openhouseai*|*.config/openhouseai*|*.smallphoneai/logs*|\
    *"/data/data/com.termux/files/usr/bin/service-manager"*|*" smallphoneai "*|*" openhouse "*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

kill_pid() {
  local pid="$1"
  local signal="${2:-TERM}"
  [ -n "$pid" ] && [ "$pid" != "$$" ] || return 0
  kill "-$signal" "$pid" 2>/dev/null || true
}

list_matching_pids() {
  local pattern="$1"
  ps -ef 2>/dev/null | awk -v self="$$" -v pat="$pattern" '
    NR > 1 {
      pid = $2
      if (pid ~ /^[0-9]+$/ && pid != self && $0 ~ pat) print pid
    }
  ' | sort -rn | uniq
}

kill_pattern() {
  local label="$1"
  local pattern="$2"
  local pids

  pids="$(list_matching_pids "$pattern" || true)"
  if [ -z "$pids" ]; then
    log "no process matched: $label"
    return 0
  fi

  log "stopping $label: $pids"
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done
}

kill_pattern_force() {
  local label="$1"
  local pattern="$2"
  local pids

  pids="$(list_matching_pids "$pattern" || true)"
  if [ -z "$pids" ]; then
    return 0
  fi

  log "force stopping $label: $pids"
  for pid in $pids; do
    kill -KILL "$pid" 2>/dev/null || true
  done
}

kill_openhouse_pidfiles() {
  local pid_file pid cmd
  for root in "$HOME/.smallphoneai" "$HOME/.config/openhouseai" "$HOME/.config/service-manager" "$HOME/.local/share/openhouseai"; do
    [ -d "$root" ] || continue
    while IFS= read -r pid_file; do
      pid="$(sed -n '1s/[^0-9].*$//p' "$pid_file" 2>/dev/null || true)"
      [ -n "$pid" ] || continue
      [ -d "/proc/$pid" ] || continue
      cmd="$(pid_cmdline "$pid")"
      if is_openhouse_cmdline "$cmd"; then
        log "stopping pidfile target: $pid_file -> $pid"
        kill_pid "$pid" TERM
      else
        log "leaving non-OpenHouse pidfile target alone: $pid_file -> $pid"
      fi
    done < <(find "$root" -type f -name '*.pid' -print 2>/dev/null || true)
  done
}

stop_openhouse_processes() {
  local openhouse_processes
  local openhouse_proot
  local generic_ubuntu_proot

  openhouse_processes='run-manifest_full[.]sh|openhouseai-bootstrap[.]sh|[.]smallphoneai-bootstrap|openhouse-pi-agent-sentinel|openhouse-pi-web-start|openhouse-aionui-web-start|smallphoneai-repos|[.]local/share/openhouseai|[.]config/openhouseai|[.]smallphoneai/logs'
  openhouse_proot='(proot-distro|proot|loader).*ubuntu.*(openhouse|smallphone|aionui|pi-agent|pi-web|smallphoneai-repos|[.]local/share/openhouseai)'
  generic_ubuntu_proot='proot-distro.*ubuntu|proot.*ubuntu|loader.*ubuntu'

  stop_managed_services_via_service_manager
  kill_openhouse_pidfiles
  kill_pattern "OpenHouse wrappers and paths" "$openhouse_processes"
  kill_pattern "OpenHouse Ubuntu/proot service processes" "$openhouse_proot"
  kill_pattern "service-manager" '(^|/| )service-manager([[:space:]]|$)|smallphoneai-repos/service-manager|/bin/service-manager([[:space:]]|$)'
  if [ "$mode" = "--full" ]; then
    kill_pattern "Ubuntu proot processes (--full)" "$generic_ubuntu_proot"
  fi
  sleep 1
  kill_pattern_force "OpenHouse wrappers and paths" "$openhouse_processes"
  kill_pattern_force "OpenHouse Ubuntu/proot service processes" "$openhouse_proot"
  kill_pattern_force "service-manager" '(^|/| )service-manager([[:space:]]|$)|smallphoneai-repos/service-manager|/bin/service-manager([[:space:]]|$)'
  if [ "$mode" = "--full" ]; then
    kill_pattern_force "Ubuntu proot processes (--full)" "$generic_ubuntu_proot"
  fi
}

reset_bootstrap_state_preserve_assets() {
  local bootstrap="$HOME/.smallphoneai-bootstrap"
  [ -d "$bootstrap" ] || return 0

  log "clearing bootstrap mutable state while preserving synced assets"
  rm -rf \
    "$bootstrap/state" \
    "$bootstrap/logs" \
    "$bootstrap/tmp" \
    "$bootstrap/.stage-state" \
    "$bootstrap/.running" \
    "$bootstrap"/*.running \
    "$bootstrap"/smallphoneai-env-probe-ubuntu.sh \
    "$bootstrap"/openhouse-termux-ubuntu.sh \
    "$bootstrap"/ensure-openhouse-workspace-ubuntu.sh \
    2>/dev/null || true
}

remove_openhouse_state() {
  log "removing OpenHouse runtime state"
  reset_bootstrap_state_preserve_assets
  rm -rf \
    "$HOME/.smallphoneai" \
    "$HOME/.maintainer-logs" \
    "$HOME/smallphoneai-repos" \
    "$HOME/.config/openhouseai" \
    "$HOME/.config/service-manager" \
    "$HOME/.local/share/openhouseai" \
    "$HOME/.pi" \
    2>/dev/null || true
  mkdir -p "$TMPDIR" "$HOME/.config" "$HOME/.local/share"
}

remove_ubuntu_rootfs() {
  log "removing Ubuntu rootfs"
  if command -v proot-distro >/dev/null 2>&1; then
    proot-distro remove ubuntu >/dev/null 2>&1 || log "proot-distro remove ubuntu failed; falling back to path cleanup"
  else
    log "proot-distro is not installed; using path cleanup"
  fi
  rm -rf \
    "$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu" \
    "$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu-bak" \
    "$PREFIX/etc/proot-distro/ubuntu.sh" \
    2>/dev/null || true
}

stop_openhouse_processes
remove_openhouse_state
if [ "$mode" = "--full" ]; then
  remove_ubuntu_rootfs
fi

log "reset complete: $mode"
REMOTE

log "done"
