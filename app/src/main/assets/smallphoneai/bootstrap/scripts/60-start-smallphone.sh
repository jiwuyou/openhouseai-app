#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
}

run_logged() {
  log "+ $*"
  "$@"
}

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

if is_termux && [ "${SMALLPHONEAI_START_IN_UBUNTU:-1}" = "1" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
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
        SMALLPHONEAI_COMPONENT_REPO_ROOT="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-/root/smallphoneai-repos}" \
        SMALLPHONEAI_SERVICE_MANAGER_DIR="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-}" \
        SMALLPHONEAI_CC_CONNECT_DIR="${SMALLPHONEAI_CC_CONNECT_DIR:-}" \
        SMALLPHONEAI_SMALLPHONE_DIR="${SMALLPHONEAI_SMALLPHONE_DIR:-}" \
        SMALLPHONEAI_SERVICE_MANAGER_BIND="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}" \
        SMALLPHONEAI_CC_CONNECT_DISABLED="${SMALLPHONEAI_CC_CONNECT_DISABLED:-}" \
        SMALLPHONEAI_DISABLE_CC_CONNECT="${SMALLPHONEAI_DISABLE_CC_CONNECT:-}" \
        SMALLPHONEAI_CC_CONNECT_URL="${SMALLPHONEAI_CC_CONNECT_URL:-}" \
        SMALLPHONEAI_SMALLPHONE_CORE_URL="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-}" \
        SMALLPHONEAI_SMALLPHONE_URL="${SMALLPHONEAI_SMALLPHONE_URL:-}" \
        SMALLPHONEAI_START_READY_TIMEOUT="${SMALLPHONEAI_START_READY_TIMEOUT:-}" \
        SMALLPHONEAI_UBUNTU_RUNTIME_KEEPALIVE=1 \
        SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-}" \
        SERVICE_MANAGER_TOKEN="${SERVICE_MANAGER_TOKEN:-}" \
        SMALLPHONE_SERVICE_MANAGER_TOKEN="${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}" \
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
    cc_probe_url="${SMALLPHONEAI_CC_CONNECT_URL:-http://127.0.0.1:21040/}"
    core_probe_url="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-http://127.0.0.1:22000/}"
    phone_probe_url="${SMALLPHONEAI_SMALLPHONE_URL:-http://127.0.0.1:22082/}"
    cc_disabled=0
    case "${SMALLPHONEAI_CC_CONNECT_DISABLED:-${SMALLPHONEAI_DISABLE_CC_CONNECT:-}}" in
      1|true|TRUE|True|yes|YES|Yes|on|ON|On)
        cc_disabled=1
        ;;
    esac

    log "等待 Ubuntu runtime supervisor 就绪（最长 ${timeout}s）。"
    waited=0
    while [ "$waited" -le "$timeout" ]; do
      missing=""
      if ! command -v curl >/dev/null 2>&1 || ! curl -fsS --max-time 2 "$sm_probe_url/api/v1/health" >/dev/null 2>&1; then
        missing="${missing:+$missing, }service-manager($sm_probe_url)"
      fi
      if ! command -v curl >/dev/null 2>&1 || ! curl -fsS --max-time 2 "$phone_probe_url" >/dev/null 2>&1; then
        missing="${missing:+$missing, }SmallPhone($phone_probe_url)"
      fi
      if ! command -v curl >/dev/null 2>&1 || ! curl -fsS --max-time 2 "$core_probe_url" >/dev/null 2>&1; then
        missing="${missing:+$missing, }SmallPhone core($core_probe_url)"
      fi
      if [ "$cc_disabled" != "1" ] && { ! command -v curl >/dev/null 2>&1 || ! curl -fsS --max-time 2 "$cc_probe_url" >/dev/null 2>&1; }; then
        missing="${missing:+$missing, }cc-connect($cc_probe_url)"
      fi
      if [ -z "$missing" ]; then
        log "Ubuntu runtime supervisor 已就绪。"
        log "入口：service-manager=$sm_probe_url, SmallPhone=$phone_probe_url, SmallPhone core=$core_probe_url, cc-connect=$cc_probe_url"
        exit 0
      fi
      if [ "$waited" -eq 0 ] || [ $((waited % 10)) -eq 0 ]; then
        log "等待就绪中：$missing"
      fi
      sleep 2
      waited=$((waited + 2))
    done

    warn "Ubuntu runtime supervisor 未在 ${timeout}s 内完全就绪。日志：$runtime_log"
    if [ -f "$runtime_log" ]; then
      tail -n 120 "$runtime_log" >&2 || true
    fi
    exit 0
  fi
  warn "Ubuntu 尚不可用，将在当前 Termux 环境尝试启动。"
fi

repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$HOME/smallphoneai-repos}"
default_path() {
  local dev_path="$1"
  local repo_name="$2"
  if [ -d "$dev_path" ]; then
    printf '%s\n' "$dev_path"
  else
    printf '%s/%s\n' "$repo_root" "$repo_name"
  fi
}

service_manager_dir="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-$(default_path /root/projects/service-manager service-manager)}"
cc_connect_dir="${SMALLPHONEAI_CC_CONNECT_DIR:-$(default_path /root/cc-connect-fresh openhouse-connect)}"
smallphone_dir="${SMALLPHONEAI_SMALLPHONE_DIR:-$(default_path /root/projects/smallphone/smallphone-active smallphone-active)}"
bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
sm_url="${SERVICE_MANAGER_URL:-http://$bind}"
cc_url="${SMALLPHONEAI_CC_CONNECT_URL:-http://127.0.0.1:21040/}"
smallphone_core_url="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-http://127.0.0.1:22000/}"
smallphone_url="${SMALLPHONEAI_SMALLPHONE_URL:-http://127.0.0.1:22082/}"
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
  if ! probe_url "$smallphone_url"; then
    append_readiness_missing "SmallPhone($smallphone_url)"
  fi
  if ! probe_url "$smallphone_core_url"; then
    append_readiness_missing "SmallPhone core($smallphone_core_url)"
  fi
  if [ "$cc_connect_disabled" != "1" ] && ! probe_url "$cc_url"; then
    append_readiness_missing "cc-connect($cc_url)"
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

  log "等待 SmallPhone 运行栈最终就绪（最长 ${timeout}s）。"
  while true; do
    if is_final_readiness_ready; then
      log "SmallPhone 运行栈已就绪。"
      return 0
    fi

    if [ "$waited" -ge "$timeout" ]; then
      warn "SmallPhone 运行栈未在 ${timeout}s 内就绪：$readiness_missing"
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
  warn "找不到 service-manager。请先运行：bash bootstrap.sh components"
  exit 2
fi

export PATH="$(dirname "$service_manager_bin"):$PATH"

mkdir -p "$log_dir"
if is_service_manager_ready; then
  log "service-manager 已可访问：$sm_url"
else
  log "正在启动 service-manager：$bind"
  nohup "$service_manager_bin" serve --bind "$bind" > "$log_dir/service-manager.log" 2>&1 < /dev/null &
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

run_register_if_present() {
  local name="$1"
  local dir="$2"
  local path="$dir/scripts/register-service.sh"
  if [ -f "$path" ]; then
    chmod +x "$path"
    log "$name: 刷新 service-manager 注册。"
    (cd "$dir" && run_logged "./scripts/register-service.sh") || warn "$name: register-service.sh 执行失败，继续尝试启动已注册服务。"
  else
    warn "$name: 缺少注册入口，跳过：$path"
  fi
}

run_register_if_present "cc-connect/openhouse-connect" "$cc_connect_dir"
run_register_if_present "SmallPhone" "$smallphone_dir"

if ! command -v curl >/dev/null 2>&1; then
  warn "缺少 curl，无法调用 service-manager 启动 local-stack。"
  exit 1
fi

cc_connect_disabled=0
if is_truthy "${SMALLPHONEAI_CC_CONNECT_DISABLED:-}" || is_truthy "${SMALLPHONEAI_DISABLE_CC_CONNECT:-}"; then
  cc_connect_disabled=1
fi

sm_token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
if [ -z "$sm_token" ]; then
  sm_token="$("$service_manager_bin" token show 2>/dev/null | tr -d '\r\n' || true)"
fi

if [ -z "$sm_token" ]; then
  warn "无法获取 service-manager token，无法启动 group:local-stack。"
  exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-start.XXXXXX")"
cleanup() {
  rm -rf "$work_dir" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT HUP TERM

curl_cfg="$work_dir/curl.cfg"
printf 'header = "Authorization: Bearer %s"\n' "$sm_token" > "$curl_cfg"

log "正在通过 service-manager 启动 group:local-stack。"
if curl -q -fsS --max-time 10 -X POST -K "$curl_cfg" "$sm_url/api/v1/groups/local-stack/start" >/dev/null; then
  log "SmallPhone 运行栈启动请求已提交。"
else
  warn "group:local-stack 启动失败；请确认组件已注册。"
  exit 1
fi

if ! wait_for_final_readiness; then
  warn "继续输出最终状态 JSON；未就绪项会在状态中体现。"
fi

if [ "$cc_connect_disabled" = "1" ]; then
  log "入口：service-manager=$sm_url, SmallPhone=$smallphone_url, SmallPhone core=$smallphone_core_url, cc-connect=disabled"
else
  log "入口：service-manager=$sm_url, SmallPhone=$smallphone_url, SmallPhone core=$smallphone_core_url, cc-connect=$cc_url"
fi

if [ "${SMALLPHONEAI_UBUNTU_RUNTIME_KEEPALIVE:-0}" = "1" ]; then
  log "Ubuntu runtime supervisor 保持运行中。"
  while true; do
    sleep 3600
  done
fi
