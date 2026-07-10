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

ensure_tmpdir

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
    command -v service-manager
    return 0
  fi
  for candidate in \
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/target/release/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/target/debug/service-manager"; do
    if [ -x "$candidate" ]; then
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

ensure_termux_service_manager() {
  local bind url cfg sm_bin
  bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
  url="${SERVICE_MANAGER_URL:-http://$bind}"
  cfg="$(termux_service_manager_config_path)"

  if termux_service_manager_ready; then
    log "Termux native service-manager 已可访问：$url"
    return 0
  fi

  if command -v sv >/dev/null 2>&1; then
    log "正在通过 termux-services 拉起 service-manager。"
    sv up service-manager >/dev/null 2>&1 || true
    for _ in $(seq 1 10); do
      termux_service_manager_ready && {
        log "Termux native service-manager 已由 runit 拉起：$url"
        return 0
      }
      sleep 1
    done
  fi

  sm_bin="$(find_termux_service_manager_binary || true)"
  if [ -z "$sm_bin" ]; then
    warn "找不到 Termux native service-manager。请先运行：bash bootstrap.sh components"
    return 1
  fi

  mkdir -p "$HOME/.smallphoneai/logs" "$(dirname "$cfg")"
  log "正在 Termux native 后台启动 service-manager：$bind"
  nohup "$sm_bin" serve --config "$cfg" --bind "$bind" > "$HOME/.smallphoneai/logs/service-manager.log" 2>&1 < /dev/null &
  for _ in $(seq 1 30); do
    termux_service_manager_ready && {
      log "Termux native service-manager 已启动：$url"
      return 0
    }
    sleep 1
  done

  warn "Termux native service-manager 未能启动。日志：$HOME/.smallphoneai/logs/service-manager.log"
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
    "$sm_bin" token show --config "$cfg" 2>/dev/null | tr -d '\r\n' || true
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
      SMALLPHONEAI_START_IN_UBUNTU=0 \
      SMALLPHONEAI_UBUNTU_RUNTIME_KEEPALIVE=1 \
      proot-distro login ubuntu -- env \
        HOME="$ubuntu_runtime_home" \
        SMALLPHONEAI_UBUNTU_HOME="$ubuntu_runtime_home" \
        OPENHOUSEAI_UBUNTU_HOME="$ubuntu_runtime_home" \
        SMALLPHONEAI_COMPONENT_REPO_ROOT="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$ubuntu_repo_root}" \
        SMALLPHONEAI_ALLOW_DEV_COMPONENT_PATHS="${SMALLPHONEAI_ALLOW_DEV_COMPONENT_PATHS:-}" \
        SMALLPHONEAI_SERVICE_MANAGER_DIR="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-}" \
        SMALLPHONEAI_CC_CONNECT_DIR="${SMALLPHONEAI_CC_CONNECT_DIR:-}" \
        SMALLPHONEAI_SMALLPHONE_DIR="${SMALLPHONEAI_SMALLPHONE_DIR:-}" \
        OPENHOUSE_PI_AGENT_DIR="${OPENHOUSE_PI_AGENT_DIR:-${SMALLPHONEAI_PI_AGENT_DIR:-}}" \
        OPENHOUSE_PI_WEB_DIR="${OPENHOUSE_PI_WEB_DIR:-${SMALLPHONEAI_PI_WEB_DIR:-}}" \
        SMALLPHONEAI_SERVICE_MANAGER_BIND="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}" \
        SMALLPHONEAI_CC_CONNECT_DISABLED="${SMALLPHONEAI_CC_CONNECT_DISABLED:-}" \
        SMALLPHONEAI_DISABLE_CC_CONNECT="${SMALLPHONEAI_DISABLE_CC_CONNECT:-}" \
        SMALLPHONEAI_CC_CONNECT_HOST="${SMALLPHONEAI_CC_CONNECT_HOST:-}" \
        SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT="${SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT:-}" \
        SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT="${SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT:-}" \
        SMALLPHONEAI_SMALLPHONE_CORE_URL="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-}" \
        SMALLPHONEAI_SMALLPHONE_URL="${SMALLPHONEAI_SMALLPHONE_URL:-}" \
        OPENHOUSE_PI_WEB_URL="${OPENHOUSE_PI_WEB_URL:-${PI_WEB_URL:-}}" \
        PI_WEB_URL="${PI_WEB_URL:-}" \
        SMALLPHONEAI_START_TARGETS="${SMALLPHONEAI_START_TARGETS:-}" \
        SMALLPHONEAI_START_READY_TIMEOUT="${SMALLPHONEAI_START_READY_TIMEOUT:-}" \
        SMALLPHONEAI_UBUNTU_RUNTIME_KEEPALIVE=1 \
        OPENHOUSE_PI_RUNTIME="${OPENHOUSE_PI_RUNTIME:-${SMALLPHONEAI_PI_RUNTIME:-termux}}" \
        SMALLPHONEAI_PI_RUNTIME="${SMALLPHONEAI_PI_RUNTIME:-${OPENHOUSE_PI_RUNTIME:-termux}}" \
        SMALLPHONEAI_REQUIRE_EXTERNAL_SERVICE_MANAGER=1 \
        SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH="$(termux_service_manager_config_path)" \
        SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-http://${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}}" \
        SERVICE_MANAGER_TOKEN="${SERVICE_MANAGER_TOKEN:-$termux_sm_token}" \
        SMALLPHONE_SERVICE_MANAGER_TOKEN="${SMALLPHONE_SERVICE_MANAGER_TOKEN:-${SERVICE_MANAGER_TOKEN:-$termux_sm_token}}" \
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
    pi_web_probe_url="${OPENHOUSE_PI_WEB_URL:-${PI_WEB_URL:-http://127.0.0.1:30141/}}"
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
      if ! command -v curl >/dev/null 2>&1 || ! curl -fsS --max-time 2 "$pi_web_probe_url" >/dev/null 2>&1; then
        missing="${missing:+$missing, }pi-web($pi_web_probe_url)"
      fi
      if ! command -v curl >/dev/null 2>&1 || ! curl -fsS --max-time 2 "$phone_probe_url" >/dev/null 2>&1; then
        missing="${missing:+$missing, }SmallPhone($phone_probe_url)"
      fi
      if ! command -v curl >/dev/null 2>&1 || ! curl -fsS --max-time 2 "$core_probe_url" >/dev/null 2>&1; then
        missing="${missing:+$missing, }SmallPhone core($core_probe_url)"
      fi
      if [ -z "$missing" ]; then
        log "Ubuntu runtime supervisor 已就绪。"
        log "入口：service-manager=$sm_probe_url, pi-web=$pi_web_probe_url, SmallPhone(兼容)=$phone_probe_url, SmallPhone core(兼容)=$core_probe_url, cc-connect=$cc_probe_label"
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
pi_agent_dir="$(component_dir_from_env "${OPENHOUSE_PI_AGENT_DIR:-${SMALLPHONEAI_PI_AGENT_DIR:-}}" pi-agent /root/projects/pi)"
pi_web_dir="$(component_dir_from_env "${OPENHOUSE_PI_WEB_DIR:-${SMALLPHONEAI_PI_WEB_DIR:-}}" pi-web /root/projects/pi-web)"
bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
sm_url="${SERVICE_MANAGER_URL:-http://$bind}"
cc_host="${SMALLPHONEAI_CC_CONNECT_HOST:-127.0.0.1}"
cc_bridge_port="${SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT:-21010}"
cc_management_port="${SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT:-21020}"
cc_url="bridge=${cc_host}:${cc_bridge_port}, management=${cc_host}:${cc_management_port}"
smallphone_core_url="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-http://127.0.0.1:22000/}"
smallphone_url="${SMALLPHONEAI_SMALLPHONE_URL:-http://127.0.0.1:22082/}"
pi_web_url="${OPENHOUSE_PI_WEB_URL:-${PI_WEB_URL:-http://127.0.0.1:30141/}}"
log_dir="${SMALLPHONEAI_LOG_DIR:-$HOME/.smallphoneai/logs}"

export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:$PATH"
export SERVICE_MANAGER_URL="$sm_url"

find_service_manager() {
  if command -v service-manager >/dev/null 2>&1; then
    command -v service-manager
    return 0
  fi
  if [ -x "$service_manager_dir/service-manager" ]; then
    printf '%s\n' "$service_manager_dir/service-manager"
    return 0
  fi
  if [ -x "$service_manager_dir/target/release/service-manager" ]; then
    printf '%s\n' "$service_manager_dir/target/release/service-manager"
    return 0
  fi
  if [ -x "$service_manager_dir/target/debug/service-manager" ]; then
    printf '%s\n' "$service_manager_dir/target/debug/service-manager"
    return 0
  fi
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
  if start_target_requested "pi-web" && ! probe_url "$pi_web_url"; then
    append_readiness_missing "pi-web($pi_web_url)"
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
if is_service_manager_ready; then
  log "service-manager 已可访问：$sm_url"
else
  if is_current_ubuntu && [ "${SMALLPHONEAI_REQUIRE_EXTERNAL_SERVICE_MANAGER:-0}" = "1" ]; then
    warn "Ubuntu 内禁止启动 service-manager；请先修复 Termux native 控制面：$sm_url"
    exit 1
  fi
  if [ -z "$service_manager_bin" ]; then
    warn "service-manager 不可访问，且没有本地二进制可启动。"
    exit 1
  fi
  log "正在启动 service-manager：$bind"
  service_manager_config_path="${SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH:-${SERVICE_MANAGER_CONFIG_PATH:-}}"
  if [ -n "$service_manager_config_path" ]; then
    nohup "$service_manager_bin" serve --config "$service_manager_config_path" --bind "$bind" > "$log_dir/service-manager.log" 2>&1 < /dev/null &
  else
    nohup "$service_manager_bin" serve --bind "$bind" > "$log_dir/service-manager.log" 2>&1 < /dev/null &
  fi
  for _ in $(seq 1 30); do
    if is_service_manager_ready; then
      log "service-manager 已启动：$sm_url"
      break
    fi
    sleep 1
  done
fi

if ! is_service_manager_ready; then
  warn "service-manager 未能启动。日志：$log_dir/service-manager.log"
  if [ -f "$log_dir/service-manager.log" ]; then
    tail -n 80 "$log_dir/service-manager.log" >&2 || true
  fi
  exit 1
fi

resolve_start_service_manager_token() {
  local token
  token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  if [ -n "$token" ]; then
    printf '%s\n' "$token"
    return 0
  fi
  if is_termux; then
    resolve_termux_service_manager_token || true
    return 0
  fi
  if [ -n "${service_manager_bin:-}" ]; then
    "$service_manager_bin" token show 2>/dev/null | tr -d '\r\n' || true
  elif command -v service-manager >/dev/null 2>&1; then
    service-manager token show 2>/dev/null | tr -d '\r\n' || true
  fi
}

sm_token="$(resolve_start_service_manager_token || true)"
if [ -z "$sm_token" ]; then
  warn "无法获取 service-manager token，无法启动 group:local-stack。"
  exit 1
fi

run_register_if_present() {
  local name="$1"
  local dir="$2"
  local path="$dir/scripts/register-service.sh"
  if [ -f "$path" ]; then
    chmod +x "$path"
    log "$name: 刷新 service-manager 注册。"
    (cd "$dir" && run_logged env \
      SERVICE_MANAGER_URL="$sm_url" \
      SERVICE_MANAGER_TOKEN="$sm_token" \
      SMALLPHONE_SERVICE_MANAGER_TOKEN="${SMALLPHONE_SERVICE_MANAGER_TOKEN:-$sm_token}" \
      bash "./scripts/register-service.sh") || warn "$name: register-service.sh 执行失败，继续尝试启动已注册服务。"
  else
    warn "$name: 缺少注册入口，跳过：$path"
  fi
}

start_target_requested "cc-connect" && run_register_if_present "cc-connect/openhouse-connect" "$cc_connect_dir"
start_target_requested "smallphone" && run_register_if_present "SmallPhone" "$smallphone_dir"
start_target_requested "pi-agent" && run_register_if_present "pi-agent" "$pi_agent_dir"
start_target_requested "pi-web" && run_register_if_present "pi-web" "$pi_web_dir"

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

start_service_if_present() {
  local service_id="$1"
  if curl -q -fsS --max-time 10 -X POST -K "$curl_cfg" "$sm_url/api/v1/services/$service_id/start" >/dev/null 2>&1; then
    log "service-manager: 已请求启动 $service_id。"
  else
    warn "service-manager: 无法单独启动 $service_id，继续等待核心状态。"
  fi
}

if [ -n "${SMALLPHONEAI_START_TARGETS:-}" ]; then
  log "正在通过 service-manager 启动指定服务：${SMALLPHONEAI_START_TARGETS}"
  start_target_requested "pi-agent" && start_service_if_present "pi-agent"
  start_target_requested "pi-web" && start_service_if_present "pi-web"
  start_target_requested "smallphone-core" && start_service_if_present "smallphone-core"
  start_target_requested "smallphone" && start_service_if_present "smallphone-frontend-beta"
elif curl -q -fsS --max-time 10 -X POST -K "$curl_cfg" "$sm_url/api/v1/groups/local-stack/start" >/dev/null; then
  log "pi 主线运行栈启动请求已提交。"
else
  warn "group:local-stack 启动失败；将单独尝试启动 pi 主线核心服务。"
  start_service_if_present "pi-agent"
  start_service_if_present "pi-web"
  start_service_if_present "smallphone-core"
  start_service_if_present "smallphone-frontend-beta"
fi

if ! wait_for_final_readiness; then
  warn "继续输出最终状态 JSON；未就绪项会在状态中体现。"
fi

if [ "$cc_connect_disabled" = "1" ]; then
  log "入口：service-manager=$sm_url, pi-web=$pi_web_url, SmallPhone(兼容)=$smallphone_url, SmallPhone core(兼容)=$smallphone_core_url, cc-connect=disabled"
else
  log "入口：service-manager=$sm_url, pi-web=$pi_web_url, SmallPhone(兼容)=$smallphone_url, SmallPhone core(兼容)=$smallphone_core_url, cc-connect=$cc_url"
fi

if [ "${SMALLPHONEAI_UBUNTU_RUNTIME_KEEPALIVE:-0}" = "1" ]; then
  log "Ubuntu runtime supervisor 保持运行中。"
  while true; do
    sleep 3600
  done
fi
