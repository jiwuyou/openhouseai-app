#!/usr/bin/env bash
set -euo pipefail

if ! declare -F log >/dev/null 2>&1; then
  log() {
    printf '[SmallPhoneAI control-plane] %s\n' "$*"
  }
fi

if ! declare -F warn >/dev/null 2>&1; then
  warn() {
    printf '[SmallPhoneAI control-plane] WARN: %s\n' "$*" >&2
  }
fi

load_termux_services_environment() {
  local candidate script_dir
  script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" && pwd)"
  for candidate in \
    "$script_dir/_termux-services-env.sh" \
    "${OPENHOUSEAI_MAINTAINER_DIR:-}/_termux-services-env.sh" \
    "${SMALLPHONEAI_MAINTAINER_DIR:-}/_termux-services-env.sh" \
    "$HOME/.smallphoneai-bootstrap/apk-assets/maintainer/_termux-services-env.sh" \
    "$HOME/.smallphoneai-bootstrap/maintainer/_termux-services-env.sh"; do
    [ -n "$candidate" ] && [ -r "$candidate" ] || continue
    . "$candidate"
    return 0
  done
  warn "缺少共享 Termux 服务环境脚本 _termux-services-env.sh。"
  return 1
}

load_termux_services_environment
oh_termux_services_environment || {
  warn "无法准备 Termux 服务环境：SVDIR=${SVDIR:-} LOGDIR=${LOGDIR:-}"
  exit 2
}

is_termux_native() {
  [ -n "${PREFIX:-}" ] \
    && [ -d "$PREFIX/bin" ] \
    && [ -d /data/data/com.termux/files ] \
    && [ "${PREFIX#/data/data/com.termux/files/}" != "$PREFIX" ]
}

find_canonical_config() {
  local candidate
  for candidate in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json"; do
    [ -n "$candidate" ] && [ -r "$candidate" ] || continue
    case "$candidate" in
      */.config/service-manager/config.json) continue ;;
    esac
    printf '%s\n' "$candidate"
    return 0
  done
  return 1
}

read_config_value() {
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
  local value="${1:-}"
  case "$value" in
    http://*) value="${value#http://}" ;;
    https://*) value="${value#https://}" ;;
  esac
  value="${value%%/*}"
  case "$value" in
    "") return 1 ;;
    :*) value="127.0.0.1$value" ;;
    0.0.0.0) value="127.0.0.1:20087" ;;
    0.0.0.0:*) value="127.0.0.1:${value#0.0.0.0:}" ;;
    "::"|"[::]") value="127.0.0.1:20087" ;;
    "[::]:"*) value="127.0.0.1:${value#"[::]:"}" ;;
    :::*) value="127.0.0.1:${value#:::}" ;;
    *:*) ;;
    *[!0-9]*) value="$value:20087" ;;
    *) value="127.0.0.1:$value" ;;
  esac
  printf '%s\n' "$value"
}

config_bind() {
  local config="$1" endpoint
  endpoint="$(read_config_value "$config" \
    listen_addr listenAddr bind bind_addr bindAddr base_url baseUrl baseURL url || true)"
  normalize_bind "${endpoint:-127.0.0.1:20087}"
}

config_url() {
  local config="$1" endpoint scheme bind
  endpoint="$(read_config_value "$config" \
    listen_addr listenAddr bind bind_addr bindAddr base_url baseUrl baseURL url || true)"
  case "$endpoint" in
    https://*) scheme=https ;;
    *) scheme=http ;;
  esac
  bind="$(normalize_bind "${endpoint:-127.0.0.1:20087}")" || return 1
  printf '%s://%s\n' "$scheme" "$bind"
}

find_installed_service_manager() {
  local candidate
  for candidate in \
    "$(command -v service-manager 2>/dev/null || true)" \
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/service-manager" \
    "${HOME:+$HOME/.local/bin/service-manager}"; do
    [ -n "$candidate" ] && [ -f "$candidate" ] && [ -x "$candidate" ] || continue
    printf '%s\n' "$candidate"
    return 0
  done
  return 1
}

service_manager_health_ready() {
  local url="$1"
  curl -q -fsS --max-time 2 "$url/api/v1/health" >/dev/null 2>&1
}

service_manager_auth_ready() (
  local config="$1" url="$2" token tmp_parent work_dir curl_cfg escaped_token
  token="$(read_config_value "$config" auth_token authToken || true)"
  [ -n "$token" ] || return 1

  tmp_parent="${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}"
  [ -d "$tmp_parent" ] || return 1
  work_dir="$(mktemp -d "$tmp_parent/openhouse-sm-start.XXXXXX")" || return 1
  trap 'rm -rf "$work_dir" >/dev/null 2>&1 || true' EXIT INT HUP TERM
  chmod 700 "$work_dir"
  curl_cfg="$work_dir/curl.cfg"
  escaped_token="$(printf '%s' "$token" | sed 's/\\/\\\\/g; s/"/\\"/g')"
  umask 077
  printf 'header = "Authorization: Bearer %s"\n' "$escaped_token" > "$curl_cfg"
  chmod 600 "$curl_cfg"
  curl -q -fsS --max-time 3 -K "$curl_cfg" "$url/api/v1/services" >/dev/null 2>&1
)

diagnostic_result() {
  printf '[SmallPhoneAI control-plane] diagnostic %s=%s\n' "$1" "$2"
}

diagnose_control_plane() {
  local binary="$1" config="$2" bind="$3" url="$4" pids status
  if [ -s "$config" ]; then diagnostic_result canonicalConfig ok; else diagnostic_result canonicalConfig missing; fi
  if [ -s "$HOME/.config/service-manager/config.json" ]; then diagnostic_result legacyConfig present; else diagnostic_result legacyConfig absent; fi
  if termux_runsvdir_active; then diagnostic_result runsvdir running; else diagnostic_result runsvdir stopped; fi
  if [ -x "$SVDIR/service-manager/run" ]; then diagnostic_result runitServiceFile ok; else diagnostic_result runitServiceFile missing; fi
  status="$(env SVDIR="$SVDIR" sv status service-manager 2>/dev/null || true)"
  case "$status" in run:*) diagnostic_result runitService running ;; down:*) diagnostic_result runitService stopped ;; *) diagnostic_result runitService unknown ;; esac
  pids="$(service_manager_serve_pids | tr '\n' ' ' | sed 's/[[:space:]]*$//' || true)"
  case "${pids:-}" in '') diagnostic_result processCount 0 ;; *' '*) diagnostic_result processCount multiple ;; *) diagnostic_result processCount 1 ;; esac
  if service_manager_instance_matches_expected "$binary" "$config" "$bind"; then diagnostic_result instanceArguments matched; else diagnostic_result instanceArguments mismatched; fi
  if service_manager_health_ready "$url"; then diagnostic_result health20087 ok; else diagnostic_result health20087 failed; fi
  if service_manager_auth_ready "$config" "$url"; then diagnostic_result canonicalAuth ok; else diagnostic_result canonicalAuth failed; fi
}

fail_control_plane_start() {
  local code="$1" message="$2" binary="${3:-}" \
    config="${4:-$HOME/.config/openhouseai/service-manager/config.json}" \
    bind="${5:-127.0.0.1:20087}" url="${6:-http://127.0.0.1:20087}"
  warn "$message"
  diagnose_control_plane "$binary" "$config" "$bind" "$url"
  return "$code"
}

service_manager_serve_pids() {
  local proc comm args
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -f '(^|/)service-manager[[:space:]]+serve([[:space:]]|$)' 2>/dev/null || true
    return 0
  fi
  for proc in /proc/[0-9]*; do
    [ -r "$proc/comm" ] && [ -r "$proc/cmdline" ] || continue
    comm="$(cat "$proc/comm" 2>/dev/null || true)"
    [ "$comm" = service-manager ] || continue
    args="$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null || true)"
    printf '%s\n' "$args" | grep -Fqx -- serve || continue
    printf '%s\n' "${proc##*/}"
  done
}

service_manager_instance_matches_expected() {
  local binary="$1" config="$2" bind="$3"
  local expected_exe pid args actual_exe total=0 matched=0
  expected_exe="$(readlink -f "$binary" 2>/dev/null || true)"
  [ -n "$expected_exe" ] || return 1
  for pid in $(service_manager_serve_pids); do
    total=$((total + 1))
    args="$(tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    actual_exe="$(readlink "/proc/$pid/exe" 2>/dev/null || true)"
    if [ "$actual_exe" = "$expected_exe" ] \
      && printf '%s\n' "$args" | grep -Fqx -- "--config" \
      && printf '%s\n' "$args" | grep -Fqx -- "$config" \
      && printf '%s\n' "$args" | grep -Fqx -- "--bind" \
      && printf '%s\n' "$args" | grep -Fqx -- "$bind"; then
      matched=$((matched + 1))
    fi
  done
  [ "$total" -eq 1 ] && [ "$matched" -eq 1 ]
}

termux_runsvdir_active() {
  oh_termux_runsvdir_active
}

ensure_termux_services_daemon() {
  oh_start_termux_services_daemon
}

service_manager_runit_ready() {
  local service_root="$SVDIR"
  local status
  termux_runsvdir_active || return 1
  [ -x "$service_root/service-manager/run" ] || return 1
  status="$(env SVDIR="$service_root" sv status service-manager 2>/dev/null || true)"
  case "$status" in run:*) return 0 ;; *) return 1 ;; esac
}

canonical_port_open() {
  local bind="$1" host port
  host="${bind%:*}"
  port="${bind##*:}"
  [ -n "$host" ] && [ -n "$port" ] && [ "$host" != "$port" ] || return 1
  bash -c 'exec 3<>"/dev/tcp/$1/$2"' bash "$host" "$port" >/dev/null 2>&1
}

wait_for_canonical_service_manager() {
  local binary="$1" config="$2" bind="$3" url="$4" attempts="${5:-30}" attempt=1
  while [ "$attempt" -le "$attempts" ]; do
    if service_manager_health_ready "$url" \
      && service_manager_auth_ready "$config" "$url" \
      && service_manager_runit_ready \
      && service_manager_instance_matches_expected "$binary" "$config" "$bind"; then
      return 0
    fi
    sleep 1
    attempt=$((attempt + 1))
  done
  return 1
}

start_control_plane() {
  local config bind url binary existing_pids log_dir log_file service_root runsvdir_pid sv_status

  is_termux_native || {
    fail_control_plane_start 2 "运行中枢只允许从 Termux native 环境启动。"
    return $?
  }
  command -v curl >/dev/null 2>&1 || {
    fail_control_plane_start 2 "缺少 curl，无法验证 service-manager health 与 canonical token。"
    return $?
  }

  config="$(find_canonical_config || true)"
  if [ -z "$config" ]; then
    fail_control_plane_start 2 "未找到现有 OpenHouse canonical service-manager 配置；不会创建或恢复配置。"
    return $?
  fi
  if [ -z "$(read_config_value "$config" auth_token authToken || true)" ]; then
    fail_control_plane_start 2 "canonical service-manager 配置缺少 token；不会修改配置。" '' "$config"
    return $?
  fi
  bind="$(config_bind "$config")" || {
    fail_control_plane_start 2 "canonical service-manager bind 无效。" '' "$config"
    return $?
  }
  url="$(config_url "$config")" || {
    fail_control_plane_start 2 "canonical service-manager URL 无效。" '' "$config" "$bind"
    return $?
  }

  binary="$(find_installed_service_manager || true)"
  if [ -z "$binary" ]; then
    fail_control_plane_start 2 "未找到当前已安装的 Termux native service-manager；不会从 APK 安装或覆盖。" '' "$config" "$bind" "$url"
    return $?
  fi
  service_root="$SVDIR"
  log "Termux service environment: SVDIR=$SVDIR LOGDIR=$LOGDIR serviceRoot=$service_root"
  if ! ensure_termux_services_daemon; then
    fail_control_plane_start 2 "termux-services/runsvdir 不可用；正式启动不会回退到 nohup。" "$binary" "$config" "$bind" "$url"
    return $?
  fi
  runsvdir_pid="$(oh_termux_runsvdir_pid || true)"
  log "runit readiness: runsvdirPid=${runsvdir_pid:-unavailable} serviceManagerRunFile=$service_root/service-manager/run"

  if service_manager_health_ready "$url"; then
    if service_manager_auth_ready "$config" "$url" \
      && service_manager_runit_ready \
      && service_manager_instance_matches_expected "$binary" "$config" "$bind"; then
      log "运行中枢已由 runit 常驻并通过 canonical config 认证：$url"
      return 0
    fi
    diagnose_control_plane "$binary" "$config" "$bind" "$url"
    warn "service-manager API 可达，但 canonical 认证、唯一实例或 runit 常驻失败；见逐项 diagnostic。"
    return 1
  fi

  existing_pids="$(service_manager_serve_pids | tr '\n' ' ' | sed 's/[[:space:]]*$//' || true)"
  if [ -n "$existing_pids" ]; then
    fail_control_plane_start 1 "检测到已有 service-manager serve 进程但 canonical API 不可用；不会停止或替换。" "$binary" "$config" "$bind" "$url"
    return $?
  fi
  if canonical_port_open "$bind"; then
    fail_control_plane_start 1 "canonical 端口已被其他进程占用；不会启动第二实例。" "$binary" "$config" "$bind" "$url"
    return $?
  fi

  log_dir="${SMALLPHONEAI_LOG_DIR:-$HOME/.smallphoneai/logs}"
  umask 077
  mkdir -p "$log_dir"
  chmod 700 "$log_dir" >/dev/null 2>&1 || true
  log_file="$log_dir/service-manager.log"

  log "正在通过 termux-services 安装并启动运行中枢：binary=$binary bind=$bind"
  "$binary" install-service --config "$config" --bind "$bind" --log-file "$log_file" || {
    fail_control_plane_start 1 "service-manager install-service 失败。" "$binary" "$config" "$bind" "$url"
    return $?
  }
  [ -x "$service_root/service-manager/run" ] || {
    fail_control_plane_start 1 "service-manager runit run 文件未生成。" "$binary" "$config" "$bind" "$url"
    return $?
  }
  if ! oh_service_manager_sv_up_with_retry service-manager \
    "${SMALLPHONEAI_SERVICE_MANAGER_SV_UP_ATTEMPTS:-10}"; then
    fail_control_plane_start 1 "sv up service-manager 在有限重试后仍失败。" "$binary" "$config" "$bind" "$url"
    return $?
  fi
  sv_status="$(env SVDIR="$service_root" sv status service-manager 2>/dev/null || true)"
  log "runit service status: svStatus=${sv_status:-unavailable} healthUrl=$url/api/v1/health"

  if wait_for_canonical_service_manager "$binary" "$config" "$bind" "$url" \
    "${SMALLPHONEAI_SERVICE_MANAGER_READY_ATTEMPTS:-30}"; then
    log "运行中枢已由 runit 常驻并通过 canonical config 认证：$url"
    return 0
  fi

  env SVDIR="$service_root" sv down service-manager >/dev/null 2>&1 || true
  sv_status="$(env SVDIR="$service_root" sv status service-manager 2>/dev/null || true)"
  diagnose_control_plane "$binary" "$config" "$bind" "$url"
  warn "service-manager 未通过有限等待；runsvdirPid=${runsvdir_pid:-unavailable} serviceManagerRunFile=$service_root/service-manager/run svStatus=${sv_status:-unavailable} healthUrl=$url/api/v1/health 正式日志：$log_file"
  return 1
}

start_control_plane
