#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
}

ensure_tmpdir() {
  if [ -z "${TMPDIR:-}" ]; then
    if [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/tmp" ]; then
      TMPDIR="$PREFIX/tmp"
    else
      TMPDIR="${HOME:-.}/.tmp"
    fi
    export TMPDIR
  fi
  mkdir -p "$TMPDIR"
}

run_logged() {
  log "+ $*"
  "$@"
}

run_with_service_manager_auth() {
  local token="$1"
  local compatibility_token="${SMALLPHONE_SERVICE_MANAGER_TOKEN:-$token}"
  shift
  (
    export SERVICE_MANAGER_TOKEN="$token"
    export SMALLPHONE_SERVICE_MANAGER_TOKEN="$compatibility_token"
    "$@"
  )
}

load_service_manager_token_file() {
  local token_file="${SMALLPHONEAI_SERVICE_MANAGER_TOKEN_FILE:-}"
  local token

  [ -n "$token_file" ] || return 0
  unset SMALLPHONEAI_SERVICE_MANAGER_TOKEN_FILE
  if [ ! -r "$token_file" ]; then
    warn "service-manager token 文件不可读。"
    return 1
  fi
  if ! token="$(cat -- "$token_file")"; then
    rm -f -- "$token_file" >/dev/null 2>&1 || true
    rmdir -- "$(dirname -- "$token_file")" >/dev/null 2>&1 || true
    warn "service-manager token 文件读取失败。"
    return 1
  fi
  rm -f -- "$token_file" >/dev/null 2>&1 || true
  rmdir -- "$(dirname -- "$token_file")" >/dev/null 2>&1 || true
  [ -n "$token" ] || {
    warn "service-manager token 文件为空。"
    return 1
  }
  if [ -z "${SERVICE_MANAGER_TOKEN:-}" ]; then
    export SERVICE_MANAGER_TOKEN="$token"
  fi
  if [ -z "${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}" ]; then
    export SMALLPHONE_SERVICE_MANAGER_TOKEN="${SERVICE_MANAGER_TOKEN:-$token}"
  fi
}

create_ubuntu_service_manager_token_file() {
  local token="$1"
  [ -n "$token" ] || return 1
  printf '%s\n' "$token" \
    | env -u SERVICE_MANAGER_TOKEN -u SMALLPHONE_SERVICE_MANAGER_TOKEN \
      proot-distro login ubuntu -- sh -c '
        set -eu
        umask 077
        auth_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-sm-token.XXXXXX")"
        auth_file="$auth_dir/token"
        published=0
        cleanup_auth_file() {
          if [ "$published" != "1" ]; then
            rm -rf -- "$auth_dir" >/dev/null 2>&1 || true
          fi
        }
        trap cleanup_auth_file EXIT
        trap "exit 1" INT HUP TERM
        cat > "$auth_file"
        chmod 600 "$auth_file"
        published=1
        printf "%s\n" "$auth_file"
      '
}

cleanup_ubuntu_service_manager_token_file() {
  local token_file="${1:-}"
  [ -n "$token_file" ] || return 0
  env -u SERVICE_MANAGER_TOKEN -u SMALLPHONE_SERVICE_MANAGER_TOKEN \
    proot-distro login ubuntu -- sh -c '
      rm -f -- "$1" >/dev/null 2>&1 || true
      rmdir -- "$(dirname -- "$1")" >/dev/null 2>&1 || true
    ' sh "$token_file" >/dev/null 2>&1 || true
}

ensure_tmpdir
if [ -n "${SMALLPHONEAI_SERVICE_MANAGER_TOKEN_FILE:-}" ]; then
  load_service_manager_token_file || exit 1
fi

probe_tcp() {
  local host="${1:-}"
  local port="${2:-}"
  case "$host" in
    ""|*[!A-Za-z0-9_.-]*)
      return 1
      ;;
  esac
  case "$port" in
    ""|*[!0-9]*)
      return 1
      ;;
  esac
  if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ] || ! command -v timeout >/dev/null 2>&1; then
    return 1
  fi
  timeout 2 bash -c ': >/dev/tcp/$1/$2' _ "$host" "$port" >/dev/null 2>&1
}

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

openhouse_pi_runtime() {
  local runtime="${OPENHOUSE_PI_RUNTIME:-${SMALLPHONEAI_PI_RUNTIME:-termux}}"
  runtime="$(printf '%s' "$runtime" | tr '[:upper:]' '[:lower:]')"
  case "$runtime" in
    ubuntu|proot|ubuntu-proot)
      printf 'ubuntu'
      ;;
    *)
      printf 'termux'
      ;;
  esac
}

should_start_in_ubuntu() {
  case "${SMALLPHONEAI_START_IN_UBUNTU:-}" in
    1|true|TRUE|True|yes|YES|Yes|on|ON|On)
      return 0
      ;;
    0|false|FALSE|False|no|NO|No|off|OFF|Off)
      return 1
      ;;
  esac
  [ "$(openhouse_pi_runtime)" = "ubuntu" ]
}

termux_service_manager_config_path() {
  local termux_home
  termux_home="${OPENHOUSEAI_TERMUX_HOME:-$HOME}"
  printf '%s\n' "${SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH:-${SERVICE_MANAGER_CONFIG_PATH:-$termux_home/.config/openhouseai/service-manager/config.json}}"
}

find_termux_service_manager_binary() {
  local candidate
  if command -v service-manager >/dev/null 2>&1; then
    candidate="$(command -v service-manager)"
    if [ "$("$candidate" --version 2>/dev/null | tr -d '\r\n')" = "service-manager 0.3.4" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi
  for candidate in \
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/target/release/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/target/debug/service-manager"; do
    if [ -x "$candidate" ] \
      && [ "$("$candidate" --version 2>/dev/null | tr -d '\r\n')" = "service-manager 0.3.4" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

termux_service_manager_ready() {
  local url="${SERVICE_MANAGER_URL:-http://${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}}"
  command -v curl >/dev/null 2>&1 || return 1
  curl -fsS --max-time 2 "$url/api/v1/health" >/dev/null 2>&1 \
    || curl -fsS --max-time 2 "$url/" >/dev/null 2>&1
}

termux_service_manager_config_token() {
  local sm_bin cfg
  sm_bin="$(find_termux_service_manager_binary || true)"
  cfg="$(termux_service_manager_config_path)"
  [ -n "$sm_bin" ] || return 1
  "$sm_bin" token show --config "$cfg" 2>/dev/null | head -n 1 | tr -d '\r\n'
}

termux_service_manager_auth_ready() (
  local token="$1"
  local url="${SERVICE_MANAGER_URL:-http://${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}}"
  local work_dir curl_cfg

  [ -n "$token" ] || return 1
  command -v curl >/dev/null 2>&1 || return 1
  umask 077
  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-sm-auth.XXXXXX")" || return 1
  trap 'rm -rf "$work_dir" >/dev/null 2>&1 || true' EXIT
  trap 'exit 1' INT HUP TERM
  curl_cfg="$work_dir/curl.cfg"
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$curl_cfg"
  chmod 600 "$curl_cfg"
  curl -q -fsS --max-time 3 -K "$curl_cfg" "$url/api/v1/services" >/dev/null 2>&1
)

termux_service_manager_ready_for_registration() {
  local token
  termux_service_manager_runit_ready || return 1
  termux_service_manager_instance_matches_expected || return 1
  termux_service_manager_ready || return 1
  token="$(termux_service_manager_config_token || true)"
  [ -n "$token" ] || return 1
  termux_service_manager_auth_ready "$token"
}

termux_service_manager_serve_pids() {
  local proc comm args
  for proc in /proc/[0-9]*; do
    [ -r "$proc/comm" ] && [ -r "$proc/cmdline" ] || continue
    comm="$(cat "$proc/comm" 2>/dev/null || true)"
    [ "$comm" = "service-manager" ] || continue
    args="$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null || true)"
    printf '%s\n' "$args" | grep -Fqx -- "serve" || continue
    printf '%s\n' "${proc##*/}"
  done
}

termux_service_manager_instance_matches_expected() {
  local cfg bind sm_bin expected_exe pid args actual_exe total=0 matched=0
  cfg="$(termux_service_manager_config_path)"
  bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
  sm_bin="$(find_termux_service_manager_binary || true)"
  [ -n "$sm_bin" ] || return 1
  expected_exe="$(readlink -f "$sm_bin" 2>/dev/null || true)"
  [ -n "$expected_exe" ] || return 1
  for pid in $(termux_service_manager_serve_pids); do
    total=$((total + 1))
    args="$(tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    actual_exe="$(readlink "/proc/$pid/exe" 2>/dev/null || true)"
    if [ "$actual_exe" = "$expected_exe" ] \
      && printf '%s\n' "$args" | grep -Fqx -- "--config" \
      && printf '%s\n' "$args" | grep -Fqx -- "$cfg" \
      && printf '%s\n' "$args" | grep -Fqx -- "--bind" \
      && printf '%s\n' "$args" | grep -Fqx -- "$bind"; then
      matched=$((matched + 1))
    fi
  done
  [ "$total" -eq 1 ] && [ "$matched" -eq 1 ]
}

stop_stale_termux_service_manager() {
  local pid pids service_root

  service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"
  if command -v sv >/dev/null 2>&1; then
    env SVDIR="$service_root" sv down service-manager >/dev/null 2>&1 || true
  fi
  pids="$(termux_service_manager_serve_pids)"
  for pid in $pids; do
    kill "$pid" >/dev/null 2>&1 || true
  done
  for _ in $(seq 1 10); do
    [ -z "$(termux_service_manager_serve_pids)" ] && return 0
    sleep 1
  done
  for pid in $(termux_service_manager_serve_pids); do
    kill -9 "$pid" >/dev/null 2>&1 || true
  done
  sleep 1
  [ -z "$(termux_service_manager_serve_pids)" ]
}

termux_runsvdir_active() {
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

ensure_termux_services_daemon() {
  local service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"

  command -v service-daemon >/dev/null 2>&1 || {
    warn "缺少 service-daemon；请先安装 termux-services。"
    return 1
  }
  command -v sv >/dev/null 2>&1 || {
    warn "缺少 sv；请先安装 termux-services。"
    return 1
  }
  [ -d "$service_root" ] || {
    warn "termux-services 服务目录不存在：$service_root"
    return 1
  }
  service-daemon start >/dev/null 2>&1 || true
  for _ in $(seq 1 10); do
    termux_runsvdir_active && return 0
    sleep 1
  done
  warn "termux-services 未能启动 runsvdir：$service_root"
  return 1
}

termux_service_manager_runit_ready() {
  local service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"
  local status

  termux_runsvdir_active || return 1
  [ -x "$service_root/service-manager/run" ] || return 1
  status="$(env SVDIR="$service_root" sv status service-manager 2>/dev/null || true)"
  case "$status" in
    run:*) return 0 ;;
    *) return 1 ;;
  esac
}

ensure_termux_service_manager() {
  local bind url cfg sm_bin log_file service_root
  bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
  url="${SERVICE_MANAGER_URL:-http://$bind}"
  cfg="$(termux_service_manager_config_path)"
  log_file="$HOME/.smallphoneai/logs/service-manager.log"
  service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"

  if termux_service_manager_ready_for_registration; then
    log "Termux native service-manager 已可访问：$url"
    return 0
  fi
  if [ -n "$(termux_service_manager_serve_pids)" ] || termux_service_manager_ready; then
    warn "检测到非预期或认证不匹配的 service-manager；将按 OpenHouse 专用 config 重启。"
    stop_stale_termux_service_manager || return 1
  fi

  sm_bin="$(find_termux_service_manager_binary || true)"
  if [ -z "$sm_bin" ]; then
    warn "找不到 Termux native service-manager。请先运行：bash bootstrap.sh components"
    return 1
  fi

  ensure_termux_services_daemon || return 1
  mkdir -p "$HOME/.smallphoneai/logs" "$(dirname "$cfg")"
  log "正在通过 termux-services 安装并拉起 service-manager。"
  "$sm_bin" install-service --config "$cfg" --bind "$bind" --log-file "$log_file" || {
    warn "service-manager install-service 失败。"
    return 1
  }
  [ -x "$service_root/service-manager/run" ] || {
    warn "service-manager runit run 文件未生成：$service_root/service-manager/run"
    return 1
  }
  env SVDIR="$service_root" sv up service-manager || {
    warn "sv up service-manager 失败。"
    return 1
  }
  for _ in $(seq 1 30); do
    termux_service_manager_ready_for_registration && {
      log "Termux native service-manager 已由 runit 拉起：$url"
      return 0
    }
    sleep 1
  done

  env SVDIR="$service_root" sv status service-manager >&2 || true
  warn "Termux native service-manager 未能以唯一 canonical runit 实例通过 health/token 验证。正式日志：$log_file"
  return 1
}

resolve_termux_service_manager_token() {
  local token sm_bin cfg
  token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  if [ -n "$token" ]; then
    printf '%s\n' "$token"
    return 0
  fi
  sm_bin="$(find_termux_service_manager_binary || true)"
  cfg="$(termux_service_manager_config_path)"
  if [ -n "$sm_bin" ]; then
    "$sm_bin" token show --config "$cfg" 2>/dev/null | head -n 1 | tr -d '\r\n' || true
  fi
}

if is_termux && should_start_in_ubuntu; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    ubuntu_runtime_home="${SMALLPHONEAI_UBUNTU_HOME:-${OPENHOUSEAI_UBUNTU_HOME:-/root}}"
    ubuntu_repo_root="${SMALLPHONEAI_UBUNTU_COMPONENT_REPO_ROOT:-${OPENHOUSEAI_UBUNTU_COMPONENT_REPO_ROOT:-$ubuntu_runtime_home/smallphoneai-repos}}"
    ensure_termux_service_manager || exit 1
    termux_sm_token="$(resolve_termux_service_manager_token || true)"
    if [ -z "$termux_sm_token" ]; then
      warn "无法获取 Termux native service-manager token。"
      exit 1
    fi
    runtime_dir="${SMALLPHONEAI_TERMUX_RUNTIME_DIR:-$HOME/.smallphoneai/runtime}"
    runtime_log_dir="${SMALLPHONEAI_TERMUX_LOG_DIR:-$HOME/.smallphoneai/logs}"
    runtime_pid_file="$runtime_dir/ubuntu-runtime.pid"
    runtime_log="$runtime_log_dir/ubuntu-runtime.log"
    mkdir -p "$runtime_dir" "$runtime_log_dir"

    if [ -f "$runtime_pid_file" ]; then
      old_pid="$(cat "$runtime_pid_file" 2>/dev/null || true)"
      if [ -n "$old_pid" ] && kill -0 "$old_pid" >/dev/null 2>&1; then
        log "正在停止旧的 Ubuntu runtime supervisor：pid=$old_pid"
        kill "$old_pid" >/dev/null 2>&1 || true
        sleep 1
      fi
    fi

    log "正在后台启动 Ubuntu runtime supervisor。日志：$runtime_log"
    (
      ubuntu_token_file=""
      trap 'cleanup_ubuntu_service_manager_token_file "$ubuntu_token_file"' EXIT
      trap 'exit 1' INT HUP TERM
      ubuntu_token_file="$(create_ubuntu_service_manager_token_file "$termux_sm_token")" || {
        warn "无法为 Ubuntu 创建 service-manager 临时认证文件。"
        exit 1
      }
      env -u SERVICE_MANAGER_TOKEN -u SMALLPHONE_SERVICE_MANAGER_TOKEN \
        proot-distro login ubuntu -- env \
        HOME="$ubuntu_runtime_home" \
        SMALLPHONEAI_START_IN_UBUNTU=0 \
        SMALLPHONEAI_UBUNTU_HOME="$ubuntu_runtime_home" \
        OPENHOUSEAI_UBUNTU_HOME="$ubuntu_runtime_home" \
        SMALLPHONEAI_COMPONENT_REPO_ROOT="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$ubuntu_repo_root}" \
        SMALLPHONEAI_ALLOW_DEV_COMPONENT_PATHS="${SMALLPHONEAI_ALLOW_DEV_COMPONENT_PATHS:-}" \
        SMALLPHONEAI_SERVICE_MANAGER_DIR="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-}" \
        SMALLPHONEAI_CC_CONNECT_DIR="${SMALLPHONEAI_CC_CONNECT_DIR:-}" \
        SMALLPHONEAI_SMALLPHONE_DIR="${SMALLPHONEAI_SMALLPHONE_DIR:-}" \
        OPENHOUSE_PI_AGENT_DIR="${OPENHOUSE_PI_AGENT_DIR:-${SMALLPHONEAI_PI_AGENT_DIR:-}}" \
        SMALLPHONEAI_SERVICE_MANAGER_BIND="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}" \
        SMALLPHONEAI_CC_CONNECT_DISABLED="${SMALLPHONEAI_CC_CONNECT_DISABLED:-}" \
        SMALLPHONEAI_DISABLE_CC_CONNECT="${SMALLPHONEAI_DISABLE_CC_CONNECT:-}" \
        SMALLPHONEAI_CC_CONNECT_HOST="${SMALLPHONEAI_CC_CONNECT_HOST:-}" \
        SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT="${SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT:-}" \
        SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT="${SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT:-}" \
        SMALLPHONEAI_SMALLPHONE_CORE_URL="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-}" \
        SMALLPHONEAI_SMALLPHONE_URL="${SMALLPHONEAI_SMALLPHONE_URL:-}" \
        SMALLPHONEAI_START_TARGETS="${SMALLPHONEAI_START_TARGETS:-pi-agent}" \
        SMALLPHONEAI_START_READY_TIMEOUT="${SMALLPHONEAI_START_READY_TIMEOUT:-}" \
        SMALLPHONEAI_UBUNTU_RUNTIME_KEEPALIVE=1 \
        OPENHOUSE_PI_RUNTIME="${OPENHOUSE_PI_RUNTIME:-${SMALLPHONEAI_PI_RUNTIME:-termux}}" \
        SMALLPHONEAI_PI_RUNTIME="${SMALLPHONEAI_PI_RUNTIME:-${OPENHOUSE_PI_RUNTIME:-termux}}" \
        SMALLPHONEAI_REQUIRE_EXTERNAL_SERVICE_MANAGER=1 \
        SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH="$(termux_service_manager_config_path)" \
        SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-http://${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}}" \
        SMALLPHONEAI_SERVICE_MANAGER_TOKEN_FILE="$ubuntu_token_file" \
        bash -s < "$0"
    ) >"$runtime_log" 2>&1 < /dev/null &
    runtime_pid=$!
    printf '%s\n' "$runtime_pid" > "$runtime_pid_file"

    timeout="${SMALLPHONEAI_START_READY_TIMEOUT:-120}"
    case "$timeout" in
      ''|*[!0-9]*)
        timeout=120
        ;;
    esac

    sm_probe_url="${SERVICE_MANAGER_URL:-http://${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}}"
    cc_bridge_host="${SMALLPHONEAI_CC_CONNECT_HOST:-127.0.0.1}"
    cc_bridge_port="${SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT:-21010}"
    cc_management_port="${SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT:-21020}"
    cc_probe_label="bridge=${cc_bridge_host}:${cc_bridge_port}, management=${cc_bridge_host}:${cc_management_port}"
    core_probe_url="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-http://127.0.0.1:22000/}"
    phone_probe_url="${SMALLPHONEAI_SMALLPHONE_URL:-http://127.0.0.1:22082/}"
    pi_runtime_probe_host="${OPENHOUSE_PI_RUNTIME_HOST:-127.0.0.1}"
    pi_runtime_probe_port="${OPENHOUSE_PI_RUNTIME_PORT:-20765}"
    cc_disabled=0
    case "${SMALLPHONEAI_CC_CONNECT_DISABLED:-${SMALLPHONEAI_DISABLE_CC_CONNECT:-}}" in
      1|true|TRUE|True|yes|YES|Yes|on|ON|On)
        cc_disabled=1
        ;;
    esac

    log "等待 Ubuntu runtime supervisor 达到 pi 主线核心就绪条件（最长 ${timeout}s）。"
    waited=0
    while [ "$waited" -le "$timeout" ]; do
      missing=""
      if ! command -v curl >/dev/null 2>&1 || ! curl -fsS --max-time 2 "$sm_probe_url/api/v1/health" >/dev/null 2>&1; then
        missing="${missing:+$missing, }service-manager($sm_probe_url)"
      fi
      if ! probe_tcp "$pi_runtime_probe_host" "$pi_runtime_probe_port"; then
        missing="${missing:+$missing, }pi-agent(${pi_runtime_probe_host}:${pi_runtime_probe_port})"
      fi
      if [ -z "$missing" ]; then
        log "Ubuntu runtime supervisor 已就绪。"
        log "入口：service-manager=$sm_probe_url, pi-agent=${pi_runtime_probe_host}:${pi_runtime_probe_port}, SmallPhone(兼容)=$phone_probe_url, SmallPhone core(兼容)=$core_probe_url, cc-connect=$cc_probe_label"
        log "cc-connect/openhouse-connect 为可修复的可选服务，不阻塞首次进入 pi-agent。"
        exit 0
      fi
      if [ "$waited" -eq 0 ] || [ $((waited % 10)) -eq 0 ]; then
        log "等待就绪中：$missing"
      fi
      sleep 2
      waited=$((waited + 2))
    done

    warn "Ubuntu runtime supervisor 未在 ${timeout}s 内达到 pi 主线就绪条件。日志：$runtime_log"
    if [ -f "$runtime_log" ]; then
      tail -n 120 "$runtime_log" >&2 || true
    fi
    exit 0
  fi
  warn "Ubuntu 尚不可用，将在当前 Termux 环境尝试启动。"
fi

if is_termux; then
  repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$HOME/smallphoneai-repos}"
else
  repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-${SMALLPHONEAI_UBUNTU_COMPONENT_REPO_ROOT:-${OPENHOUSEAI_UBUNTU_COMPONENT_REPO_ROOT:-$HOME/smallphoneai-repos}}}"
fi
allow_dev_component_paths() {
  case "${SMALLPHONEAI_ALLOW_DEV_COMPONENT_PATHS:-0}" in
    1|true|TRUE|True|yes|YES|Yes|on|ON|On)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

normalize_component_path() {
  local path="${1:-}"
  while [ "${path%/}" != "$path" ] && [ "$path" != "/" ]; do
    path="${path%/}"
  done
  printf '%s\n' "$path"
}

is_known_dev_component_path() {
  local path
  path="$(normalize_component_path "$1")"
  case "$path" in
    /root/projects/service-manager|/root/openhouse-connect-fresh|/root/cc-connect-fresh|/root/projects/smallphone/smallphone-active)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

default_path() {
  local repo_name="$1"
  shift
  local product_path="$repo_root/$repo_name"
  if allow_dev_component_paths; then
    local dev_path
    for dev_path in "$@"; do
      if [ -d "$dev_path" ]; then
        printf '%s\n' "$dev_path"
        return
      fi
    done
  fi
  printf '%s\n' "$product_path"
}

component_dir_from_env() {
  local env_value="$1"
  local repo_name="$2"
  shift 2
  local product_path="$repo_root/$repo_name"
  if [ -n "$env_value" ]; then
    if is_known_dev_component_path "$env_value" && ! allow_dev_component_paths; then
      printf '%s\n' "$product_path"
    else
      printf '%s\n' "$env_value"
    fi
    return
  fi
  default_path "$repo_name" "$@"
}

service_manager_dir="$(component_dir_from_env "${SMALLPHONEAI_SERVICE_MANAGER_DIR:-}" service-manager /root/projects/service-manager)"
cc_connect_dir="$(component_dir_from_env "${SMALLPHONEAI_CC_CONNECT_DIR:-}" openhouse-connect /root/openhouse-connect-fresh /root/cc-connect-fresh)"
smallphone_dir="$(component_dir_from_env "${SMALLPHONEAI_SMALLPHONE_DIR:-}" smallphone-active /root/projects/smallphone/smallphone-active)"
pi_agent_dir="$(component_dir_from_env "${OPENHOUSE_PI_AGENT_DIR:-${SMALLPHONEAI_PI_AGENT_DIR:-}}" pi-runtime /root/projects/pi)"
bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
sm_url="${SERVICE_MANAGER_URL:-http://$bind}"
cc_host="${SMALLPHONEAI_CC_CONNECT_HOST:-127.0.0.1}"
cc_bridge_port="${SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT:-21010}"
cc_management_port="${SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT:-21020}"
cc_url="bridge=${cc_host}:${cc_bridge_port}, management=${cc_host}:${cc_management_port}"
smallphone_core_url="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-http://127.0.0.1:22000/}"
smallphone_url="${SMALLPHONEAI_SMALLPHONE_URL:-http://127.0.0.1:22082/}"
pi_runtime_host="${OPENHOUSE_PI_RUNTIME_HOST:-127.0.0.1}"
pi_runtime_port="${OPENHOUSE_PI_RUNTIME_PORT:-20765}"
export SMALLPHONEAI_START_TARGETS="${SMALLPHONEAI_START_TARGETS:-pi-agent}"
log_dir="${SMALLPHONEAI_LOG_DIR:-$HOME/.smallphoneai/logs}"

export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:$PATH"
export SERVICE_MANAGER_URL="$sm_url"

find_service_manager() {
  local candidate
  if command -v service-manager >/dev/null 2>&1; then
    candidate="$(command -v service-manager)"
    if ! is_termux || [ "$("$candidate" --version 2>/dev/null | tr -d '\r\n')" = "service-manager 0.3.4" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi
  for candidate in \
    "$service_manager_dir/service-manager" \
    "$service_manager_dir/target/release/service-manager" \
    "$service_manager_dir/target/debug/service-manager"; do
    [ -x "$candidate" ] || continue
    if ! is_termux || [ "$("$candidate" --version 2>/dev/null | tr -d '\r\n')" = "service-manager 0.3.4" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

service_manager_shim_dir=""
ensure_service_manager_cli_for_registration() {
  local token shim
  if command -v service-manager >/dev/null 2>&1; then
    return 0
  fi
  token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  [ -n "$token" ] || return 1
  service_manager_shim_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-sm-shim.XXXXXX")"
  shim="$service_manager_shim_dir/service-manager"
  cat > "$shim" <<'SH'
#!/usr/bin/env sh
set -eu
if [ "${1:-}" = "token" ] && [ "${2:-}" = "show" ]; then
  printf '%s\n' "${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  exit 0
fi
printf '%s\n' "service-manager shim only supports: token show" >&2
exit 2
SH
  chmod +x "$shim"
  export PATH="$service_manager_shim_dir:$PATH"
}

is_service_manager_ready() {
  if is_termux; then
    termux_service_manager_ready_for_registration
    return $?
  fi
  command -v curl >/dev/null 2>&1 || return 1
  curl -fsS --max-time 2 "$sm_url/api/v1/health" >/dev/null 2>&1 \
    || curl -fsS --max-time 2 "$sm_url/" >/dev/null 2>&1
}

is_truthy() {
  case "${1:-}" in
    1|true|TRUE|True|yes|YES|Yes|on|ON|On)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

normalize_start_target() {
  case "${1:-}" in
    pi|pi-agent)
      printf 'pi-agent'
      ;;
    web|pi-web)
      printf 'pi-web'
      ;;
    smallphone|phone)
      printf 'smallphone'
      ;;
    smallphone-core|core)
      printf 'smallphone-core'
      ;;
    cc|cc-connect|openhouse-connect)
      printf 'cc-connect'
      ;;
    *)
      printf '%s' "${1:-}"
      ;;
  esac
}

start_target_requested() {
  local target="$1"
  local rest item wanted
  [ -n "${SMALLPHONEAI_START_TARGETS:-}" ] || return 0

  rest="${SMALLPHONEAI_START_TARGETS:-}"
  while [ -n "$rest" ]; do
    case "$rest" in
      *,*)
        item="${rest%%,*}"
        rest="${rest#*,}"
        ;;
      *)
        item="$rest"
        rest=""
        ;;
    esac
    wanted="$(normalize_start_target "$item")"
    [ "$wanted" = "$target" ] && return 0
  done

  return 1
}

probe_url() {
  local url="$1"
  curl -fsS --max-time 2 "$url" >/dev/null 2>&1
}

append_readiness_missing() {
  local item="$1"
  if [ -z "$readiness_missing" ]; then
    readiness_missing="$item"
  else
    readiness_missing="$readiness_missing, $item"
  fi
}

is_final_readiness_ready() {
  readiness_missing=""

  if ! is_service_manager_ready; then
    append_readiness_missing "service-manager($sm_url)"
  fi
  if start_target_requested "pi-agent" && ! probe_tcp "$pi_runtime_host" "$pi_runtime_port"; then
    append_readiness_missing "pi-agent(${pi_runtime_host}:${pi_runtime_port})"
  fi
  if start_target_requested "smallphone" && ! probe_url "$smallphone_url"; then
    append_readiness_missing "SmallPhone($smallphone_url)"
  fi
  if start_target_requested "smallphone-core" && ! probe_url "$smallphone_core_url"; then
    append_readiness_missing "SmallPhone core($smallphone_core_url)"
  fi

  [ -z "$readiness_missing" ]
}

wait_for_final_readiness() {
  local timeout="${SMALLPHONEAI_START_READY_TIMEOUT:-120}"
  local interval=2
  local waited=0
  local next_log=0

  case "$timeout" in
    ''|*[!0-9]*)
      timeout=120
      ;;
  esac

  log "等待 pi 主线运行栈最终就绪（最长 ${timeout}s）。"
  while true; do
    if is_final_readiness_ready; then
      log "pi 主线运行栈已就绪。"
      return 0
    fi

    if [ "$waited" -ge "$timeout" ]; then
      warn "pi 主线运行栈未在 ${timeout}s 内就绪：$readiness_missing"
      return 1
    fi

    if [ "$waited" -eq 0 ] || [ "$waited" -ge "$next_log" ]; then
      log "等待就绪中：$readiness_missing"
      next_log=$((waited + 10))
    fi

    sleep "$interval"
    waited=$((waited + interval))
  done
}

service_manager_bin="$(find_service_manager || true)"
if [ -z "$service_manager_bin" ]; then
  if is_service_manager_ready && ensure_service_manager_cli_for_registration; then
    log "使用外部 service-manager 控制面：$sm_url"
  else
    warn "找不到可用 service-manager CLI，且外部控制面不可用。请先运行：bash bootstrap.sh components"
    exit 2
  fi
else
  export PATH="$(dirname "$service_manager_bin"):$PATH"
fi

mkdir -p "$log_dir"
service_manager_log_file="$log_dir/service-manager.log"
if is_termux; then
  ensure_termux_service_manager || exit 1
elif is_service_manager_ready; then
  log "使用 Termux native 外部 service-manager 控制面：$sm_url"
else
  warn "service-manager 只允许由 Termux native termux-services/runit 常驻；请先修复控制面：$sm_url"
  exit 1
fi

if ! is_service_manager_ready; then
  warn "service-manager 未能通过健康检查。正式日志：$service_manager_log_file"
  if [ -f "$service_manager_log_file" ]; then
    tail -n 80 "$service_manager_log_file" >&2 || true
  fi
  exit 1
fi

resolve_start_service_manager_token() {
  local token cfg
  token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  if [ -n "$token" ]; then
    printf '%s\n' "$token"
    return 0
  fi
  if is_termux; then
    resolve_termux_service_manager_token || true
    return 0
  fi
  cfg="${SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH:-${SERVICE_MANAGER_CONFIG_PATH:-$HOME/.config/openhouseai/service-manager/config.json}}"
  if [ -n "${service_manager_bin:-}" ]; then
    "$service_manager_bin" token show --config "$cfg" 2>/dev/null | tr -d '\r\n' || true
  elif command -v service-manager >/dev/null 2>&1; then
    service-manager token show --config "$cfg" 2>/dev/null | tr -d '\r\n' || true
  fi
}

sm_token="$(resolve_start_service_manager_token || true)"
if [ -z "$sm_token" ]; then
  warn "无法获取 service-manager token，无法启动 group:local-stack。"
  exit 1
fi

dynamic_register_pi_service() (
  local service_id="$1"
  local config_dir="${OPENHOUSEAI_CONFIG_DIR:-$HOME/.config/openhouseai}"
  local stable_service_id="$service_id"
  local spec work_dir apply_payload curl_cfg escaped_token

  [ "$service_id" = "pi-agent" ] || return 1
  [ "$service_id" != "pi-agent" ] || stable_service_id="yuanshengwuxianpi"
  spec="$config_dir/service-manager/services.d/$stable_service_id.json"
  [ -f "$spec" ] || {
    warn "$service_id: register-service.sh 未生成服务定义：$spec"
    return 1
  }
  command -v jq >/dev/null 2>&1 || {
    warn "$service_id: jq 不可用，无法生成稳定 ID registry apply 请求。"
    return 1
  }
  command -v curl >/dev/null 2>&1 || {
    warn "$service_id: curl 不可用，无法调用 service-manager 动态注册。"
    return 1
  }

  umask 077
  work_dir="$(mktemp -d "$TMPDIR/smallphoneai-pi-register.XXXXXX")" || return 1
  trap 'rm -rf "$work_dir" >/dev/null 2>&1 || true' EXIT INT HUP TERM
  apply_payload="$work_dir/registry-apply.json"
  curl_cfg="$work_dir/curl.cfg"
  jq -n --arg id "$stable_service_id" --slurpfile spec "$spec" \
    '{services: [{schemaVersion: 1, id: $id, service: $spec[0]}]}' > "$apply_payload"
  escaped_token="$(printf '%s' "$sm_token" | sed 's/\\/\\\\/g; s/"/\\"/g')"
  printf 'header = "Authorization: Bearer %s"\n' "$escaped_token" > "$curl_cfg"
  chmod 600 "$curl_cfg"

  log "$service_id: 正在将稳定 ID=$stable_service_id 的服务定义动态应用到运行中的 service-manager。"
  curl -q -fsS --max-time 10 -K "$curl_cfg" \
    -H 'Content-Type: application/json' -X POST --data-binary "@$apply_payload" \
    "$sm_url/api/v1/registry/apply" >/dev/null || {
      warn "$service_id: service-manager registry apply 失败。"
      return 1
    }
  log "$service_id: 正在调用 provider 注册：/api/v1/services/$stable_service_id/register"
  curl -q -fsS --max-time 10 -K "$curl_cfg" -X POST \
    "$sm_url/api/v1/services/$stable_service_id/register" >/dev/null || {
      warn "$service_id: service-manager provider 注册失败。"
      return 1
    }
)

run_register_if_present() {
  local name="$1"
  local dir="$2"
  local path="$dir/scripts/register-service.sh"
  if [ -f "$path" ]; then
    chmod +x "$path"
    log "$name: 刷新 service-manager 注册。"
    if (cd "$dir" \
      && run_with_service_manager_auth "$sm_token" run_logged env \
        SERVICE_MANAGER_URL="$sm_url" \
        bash "./scripts/register-service.sh" \
      && { case "$name" in pi-agent) dynamic_register_pi_service "$name" ;; *) true ;; esac; }); then
      return 0
    fi
    case "$name" in
      pi-agent)
        warn "$name: 动态注册失败，拒绝继续启动该服务。"
        return 1
        ;;
      *)
        warn "$name: register-service.sh 执行失败，继续尝试启动已注册服务。"
        return 0
        ;;
    esac
  else
    case "$name" in
      pi-agent)
        warn "$name: 缺少必要注册入口：$path"
        return 1
        ;;
      *)
        warn "$name: 缺少注册入口，跳过：$path"
        return 0
        ;;
    esac
  fi
}

start_target_requested "cc-connect" && run_register_if_present "cc-connect/openhouse-connect" "$cc_connect_dir"
start_target_requested "smallphone" && run_register_if_present "SmallPhone" "$smallphone_dir"
if start_target_requested "pi-agent"; then
  run_register_if_present "pi-agent" "$pi_agent_dir" || exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
  warn "缺少 curl，无法调用 service-manager 启动 local-stack。"
  exit 1
fi

cc_connect_disabled=0
if is_truthy "${SMALLPHONEAI_CC_CONNECT_DISABLED:-}" || is_truthy "${SMALLPHONEAI_DISABLE_CC_CONNECT:-}"; then
  cc_connect_disabled=1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-start.XXXXXX")"
cleanup() {
  rm -rf "$work_dir" >/dev/null 2>&1 || true
  if [ -n "$service_manager_shim_dir" ]; then
    rm -rf "$service_manager_shim_dir" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT HUP TERM

curl_cfg="$work_dir/curl.cfg"
printf 'header = "Authorization: Bearer %s"\n' "$sm_token" > "$curl_cfg"
chmod 600 "$curl_cfg"

start_service_if_present() {
  local service_id="$1"
  [ "$service_id" != "pi-agent" ] || service_id="yuanshengwuxianpi"
  if curl -q -fsS --max-time 10 -X POST -K "$curl_cfg" "$sm_url/api/v1/services/$service_id/start" >/dev/null 2>&1; then
    log "service-manager: 已请求启动 $service_id。"
  else
    warn "service-manager: 无法单独启动 $service_id，继续等待核心状态。"
  fi
}

if [ -n "${SMALLPHONEAI_START_TARGETS:-}" ]; then
  log "正在通过 service-manager 启动指定服务：${SMALLPHONEAI_START_TARGETS}"
  start_target_requested "pi-agent" && start_service_if_present "pi-agent"
  start_target_requested "smallphone-core" && start_service_if_present "smallphone-core"
  start_target_requested "smallphone" && start_service_if_present "smallphone-frontend-beta"
elif curl -q -fsS --max-time 10 -X POST -K "$curl_cfg" "$sm_url/api/v1/groups/local-stack/start" >/dev/null; then
  log "pi 主线运行栈启动请求已提交。"
else
  warn "group:local-stack 启动失败；将单独尝试启动 pi 主线核心服务。"
  start_service_if_present "pi-agent"
  start_service_if_present "smallphone-core"
  start_service_if_present "smallphone-frontend-beta"
fi

if ! wait_for_final_readiness; then
  warn "继续输出最终状态 JSON；未就绪项会在状态中体现。"
fi

if [ "$cc_connect_disabled" = "1" ]; then
  log "入口：service-manager=$sm_url, pi-agent=${pi_runtime_host}:${pi_runtime_port}, SmallPhone(兼容)=$smallphone_url, SmallPhone core(兼容)=$smallphone_core_url, cc-connect=disabled"
else
  log "入口：service-manager=$sm_url, pi-agent=${pi_runtime_host}:${pi_runtime_port}, SmallPhone(兼容)=$smallphone_url, SmallPhone core(兼容)=$smallphone_core_url, cc-connect=$cc_url"
fi

if [ "${SMALLPHONEAI_UBUNTU_RUNTIME_KEEPALIVE:-0}" = "1" ]; then
  log "Ubuntu runtime supervisor 保持运行中。"
  while true; do
    sleep 3600
  done
fi
