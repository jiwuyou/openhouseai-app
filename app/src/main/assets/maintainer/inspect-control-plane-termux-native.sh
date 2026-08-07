#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-inspect}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DEFAULT_CONFIG="$HOME/.config/openhouseai/service-manager/config.json"

log() {
  printf '[OpenHouse control-plane] %s\n' "$*"
}

warn() {
  printf '[OpenHouse control-plane] WARN: %s\n' "$*" >&2
}

config_path() {
  printf '%s\n' "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-${OPENHOUSE_SERVICE_MANAGER_CONFIG:-$DEFAULT_CONFIG}}"
}

config_value() {
  local config="$1"
  shift
  local key value
  for key in "$@"; do
    value="$(sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$config" | head -n 1 || true)"
    if [ -n "$value" ]; then
      printf '%s\n' "$value"
      return 0
    fi
  done
  return 1
}

normalize_bind() {
  local value="${1:-127.0.0.1:20087}"
  value="${value#http://}"
  value="${value#https://}"
  value="${value%%/*}"
  case "$value" in
    0.0.0.0:*) value="127.0.0.1:${value#0.0.0.0:}" ;;
    0.0.0.0) value="127.0.0.1:20087" ;;
    :*) value="127.0.0.1$value" ;;
    *:*) ;;
    *) value="127.0.0.1:$value" ;;
  esac
  printf '%s\n' "$value"
}

runsvdir_active() {
  local service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"
  local proc comm args
  for proc in /proc/[0-9]*; do
    [ -r "$proc/comm" ] && [ -r "$proc/cmdline" ] || continue
    comm="$(cat "$proc/comm" 2>/dev/null || true)"
    [ "$comm" = "runsvdir" ] || continue
    args="$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null || true)"
    printf '%s\n' "$args" | grep -Fqx -- "$service_root" && return 0
  done
  return 1
}

bundle_file_ok() {
  local name="$1"
  [ -f "$SCRIPT_DIR/$name" ] && [ ! -L "$SCRIPT_DIR/$name" ]
}

bundle_integrity_ok() {
  local name="$1" expected actual
  expected="$(sed -n "/\"name\"[[:space:]]*:[[:space:]]*\"$name\"/,/}/s/.*\"sha256\"[[:space:]]*:[[:space:]]*\"\([0-9a-f]\{64\}\)\".*/\1/p" "$SCRIPT_DIR/control-plane-manifest.json" | head -n 1 || true)"
  [ -n "$expected" ] || return 1
  actual="$(sha256sum "$SCRIPT_DIR/$name" 2>/dev/null | awk '{print $1}' || true)"
  [ "$expected" = "$actual" ]
}

health_ready() {
  local config="$1" bind="$2" token work_dir curl_config
  token="$(config_value "$config" auth_token authToken || true)"
  [ -n "$token" ] || return 1
  work_dir="$(mktemp -d "${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}/openhouse-control-plane.XXXXXX")" || return 1
  trap 'rm -rf "$work_dir" >/dev/null 2>&1 || true' RETURN
  curl_config="$work_dir/curl.cfg"
  umask 077
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$curl_config"
  chmod 600 "$curl_config"
  curl -q -fsS --max-time 3 "http://$bind/api/v1/health" >/dev/null \
    && curl -q -fsS --max-time 3 -K "$curl_config" "http://$bind/api/v1/services" >/dev/null
}

inspect() {
  local failed=0 config bind service_root status binary
  printf 'control_plane_mode=inspect\n'
  printf 'control_plane_script_dir=%s\n' "$SCRIPT_DIR"

  for file in start-control-plane-termux-native.sh repair-control-plane-termux-native.sh inspect-control-plane-termux-native.sh control-plane-manifest.json; do
    if bundle_file_ok "$file"; then
      printf 'control_plane_bundle_%s=present\n' "${file%.sh}"
    else
      printf 'control_plane_bundle_%s=missing\n' "${file%.sh}"
      failed=1
    fi
  done

  for file in start-control-plane-termux-native.sh repair-control-plane-termux-native.sh inspect-control-plane-termux-native.sh; do
    if bundle_integrity_ok "$file"; then
      printf 'control_plane_integrity_%s=ok\n' "${file%.sh}"
    else
      printf 'control_plane_integrity_%s=failed\n' "${file%.sh}"
      failed=1
    fi
  done

  config="$(config_path)"
  if [ ! -r "$config" ]; then
    printf 'control_plane_config=missing\n'
    printf 'control_plane_status=repair_required\n'
    printf 'control_plane_recommendation=run-first-install-or-repair\n'
    return 1
  fi
  bind="$(normalize_bind "$(config_value "$config" listen_addr listenAddr bind bind_addr bindAddr base_url baseUrl || true)")"
  printf 'control_plane_config=present\n'
  printf 'control_plane_bind=%s\n' "$bind"
  if [ -n "$(config_value "$config" auth_token authToken || true)" ]; then
    printf 'control_plane_token=present\n'
  else
    printf 'control_plane_token=missing\n'
    failed=1
  fi

  binary="$(command -v service-manager 2>/dev/null || true)"
  if [ -n "$binary" ]; then
    printf 'control_plane_service_manager=present\n'
    printf 'control_plane_service_manager_version=%s\n' "$("$binary" --version 2>/dev/null | tr -d '\r\n')"
  else
    printf 'control_plane_service_manager=missing\n'
    failed=1
  fi

  for command_name in service-daemon sv runsvdir; do
    if command -v "$command_name" >/dev/null 2>&1; then
      printf 'control_plane_command_%s=present\n' "$command_name"
    else
      printf 'control_plane_command_%s=missing\n' "$command_name"
      failed=1
    fi
  done

  service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"
  if runsvdir_active; then
    printf 'control_plane_runsvdir=running\n'
  else
    printf 'control_plane_runsvdir=stopped\n'
    failed=1
  fi
  status="$(env SVDIR="$service_root" sv status service-manager 2>&1 || true)"
  printf 'control_plane_sv_status=%s\n' "$status"
  case "$status" in run:*) ;; *) failed=1 ;; esac

  if health_ready "$config" "$bind"; then
    printf 'control_plane_health=ok\n'
  else
    printf 'control_plane_health=failed\n'
    failed=1
  fi
  if [ "$failed" -eq 0 ]; then
    printf 'control_plane_status=ready\n'
    return 0
  fi
  printf 'control_plane_status=repair_required\n'
  printf 'control_plane_recommendation=run-repair-control-plane-termux-native.sh\n'
  return 1
}

case "$MODE" in
  inspect|status) inspect ;;
  start) exec "${PREFIX:-/data/data/com.termux/files/usr}/bin/bash" "$SCRIPT_DIR/start-control-plane-termux-native.sh" ;;
  repair) exec "${PREFIX:-/data/data/com.termux/files/usr}/bin/bash" "$SCRIPT_DIR/repair-control-plane-termux-native.sh" ;;
  *)
    warn "usage: $0 inspect|status|start|repair"
    exit 2
    ;;
esac
