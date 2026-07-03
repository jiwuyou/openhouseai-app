set -euo pipefail

action="${OPENHOUSE_PI_WEB_RESCUE_ACTION:-status}"
port="${OPENHOUSE_PI_WEB_RESCUE_PORT:-__PORT__}"
case "$port" in
  ''|*[!0-9]*)
    log "AI 救援端口无效：$port"
    exit 2
    ;;
esac
if [ "$port" -lt 1024 ] || [ "$port" -gt 65535 ]; then
  log "AI 救援端口超出范围：$port（允许 1024-65535）"
  exit 2
fi

log "AI 救援：独立于 service-manager 控制 pi-web，动作=$action，端口=$port。"
require_ubuntu

run_ubuntu_logged env \
  OPENHOUSE_PI_WEB_RESCUE_ACTION="$action" \
  OPENHOUSE_PI_WEB_RESCUE_PORT="$port" \
  SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-}" \
  bash <<'OPENHOUSE_PI_WEB_RESCUE'
set -euo pipefail

log() {
  printf '[OpenHouse AI Rescue] %s\n' "$*"
}

warn() {
  printf '[OpenHouse AI Rescue] WARN: %s\n' "$*" >&2
}

truthy() {
  case "${1:-}" in
    1|true|TRUE|True|yes|YES|Yes|on|ON|On) return 0 ;;
    *) return 1 ;;
  esac
}

probe_url() {
  command -v curl >/dev/null 2>&1 || return 1
  curl -sS -o /dev/null --connect-timeout 1 --max-time 2 "$1" >/dev/null 2>&1
}

default_path() {
  local repo_name="$1"
  local dev_path="$2"
  local repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$HOME/smallphoneai-repos}"
  if [ -d "$dev_path" ]; then
    printf '%s\n' "$dev_path"
  else
    printf '%s/%s\n' "$repo_root" "$repo_name"
  fi
}

action="${OPENHOUSE_PI_WEB_RESCUE_ACTION:-status}"
port="${OPENHOUSE_PI_WEB_RESCUE_PORT:?OPENHOUSE_PI_WEB_RESCUE_PORT is required}"
case "$port" in
  ''|*[!0-9]*)
    warn "AI 救援端口无效：$port"
    exit 2
    ;;
esac
if [ "$port" -lt 1024 ] || [ "$port" -gt 65535 ]; then
  warn "AI 救援端口超出范围：$port（允许 1024-65535）"
  exit 2
fi
host="${OPENHOUSE_PI_WEB_RESCUE_HOST:-127.0.0.1}"
url="http://$host:$port/"
pi_web_dir="${OPENHOUSE_PI_WEB_DIR:-${SMALLPHONEAI_PI_WEB_DIR:-$(default_path pi-web /root/projects/pi-web)}}"
payload_root="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-/data/data/com.termux/files/home/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}"
runtime_dir="${OPENHOUSE_PI_WEB_RUNTIME_DIR:-$HOME/.local/share/openhouseai/pi-web}"
state_dir="$HOME/.smallphoneai"
log_dir="$state_dir/logs"
pid_file="$state_dir/pi-web-rescue-$port.pid"
rescue_log="$log_dir/pi-web-rescue-$port.log"
rescue_marker="openhouse-pi-web-rescue:$port:$runtime_dir"

export HOME="${HOME:-/root}"
export PI_CODING_AGENT_DIR="${PI_CODING_AGENT_DIR:-$HOME/.pi}"
export PATH="$HOME/.local/node/bin:$HOME/.local/bin:$HOME/.npm-global/bin:/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin:${PATH:-}"

find_pi_web_start() {
  if command -v openhouse-pi-web-start >/dev/null 2>&1; then
    command -v openhouse-pi-web-start
    return 0
  fi
  if [ -x "$pi_web_dir/bin/openhouse-pi-web-start" ]; then
    printf '%s\n' "$pi_web_dir/bin/openhouse-pi-web-start"
    return 0
  fi
  if [ -x "$HOME/.local/bin/openhouse-pi-web-start" ]; then
    printf '%s\n' "$HOME/.local/bin/openhouse-pi-web-start"
    return 0
  fi
  return 1
}

extract_pi_web_payload_if_missing() {
  local payload="$payload_root/pi-web.tar"
  local work_dir payload_dir
  if [ -d "$pi_web_dir" ] && [ -f "$pi_web_dir/scripts/install.sh" ]; then
    return 0
  fi
  if [ ! -f "$payload" ]; then
    warn "pi-web payload 不存在：$payload"
    return 1
  fi
  log "从 APK payload 恢复 pi-web：$payload -> $pi_web_dir"
  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-pi-web-rescue.XXXXXX")"
  tar -xf "$payload" -C "$work_dir"
  if [ -f "$work_dir/scripts/install.sh" ]; then
    payload_dir="$work_dir"
  else
    payload_dir="$(find "$work_dir" -mindepth 2 -maxdepth 3 -path '*/scripts/install.sh' -type f -print | sed 's#/scripts/install\.sh$##' | head -n 1)"
  fi
  if [ -z "${payload_dir:-}" ] || [ ! -d "$payload_dir" ]; then
    rm -rf "$work_dir"
    warn "无法识别 pi-web payload。"
    return 1
  fi
  mkdir -p "$pi_web_dir"
  cp -a "$payload_dir/." "$pi_web_dir/"
  rm -rf "$work_dir"
}

ensure_pi_web_installed() {
  extract_pi_web_payload_if_missing || true
  if pi_web_strong_check quiet; then
    return 0
  fi
  pi_web_strong_check verbose || true
  if [ ! -f "$pi_web_dir/scripts/install.sh" ]; then
    warn "缺少 pi-web 安装脚本：$pi_web_dir/scripts/install.sh"
    return 1
  fi
  log "执行 pi-web bundle 安装：$pi_web_dir/scripts/install.sh"
  (cd "$pi_web_dir" && OPENHOUSE_PI_WEB_RUNTIME_DIR="$runtime_dir" sh scripts/install.sh)
  if [ -f "$pi_web_dir/scripts/check.sh" ]; then
    log "执行 pi-web bundle 检查：$pi_web_dir/scripts/check.sh"
    (cd "$pi_web_dir" && OPENHOUSE_PI_WEB_RUNTIME_DIR="$runtime_dir" sh scripts/check.sh)
  fi
  pi_web_strong_check verbose
}

pi_web_check_warn() {
  local mode="$1"
  shift
  if [ "$mode" != "quiet" ]; then
    warn "$*"
  fi
}

pi_web_strong_check() {
  local mode="${1:-quiet}"
  local start_cmd
  if ! command -v node >/dev/null 2>&1; then
    pi_web_check_warn "$mode" "Node.js 不可用，无法启动 pi-web。"
    return 1
  fi
  start_cmd="$(find_pi_web_start || true)"
  if [ -z "$start_cmd" ] || [ ! -x "$start_cmd" ]; then
    pi_web_check_warn "$mode" "openhouse-pi-web-start 不存在或不可执行。"
    return 1
  fi
  if [ ! -f "$runtime_dir/package.json" ]; then
    pi_web_check_warn "$mode" "pi-web runtime package.json 缺失：$runtime_dir/package.json"
    return 1
  fi
  if [ ! -f "$runtime_dir/server.js" ]; then
    pi_web_check_warn "$mode" "pi-web runtime server.js 缺失：$runtime_dir/server.js"
    return 1
  fi
  if [ ! -d "$runtime_dir/node_modules" ]; then
    pi_web_check_warn "$mode" "pi-web runtime node_modules 缺失：$runtime_dir/node_modules"
    return 1
  fi
  if [ ! -d "$runtime_dir/.next/static" ]; then
    pi_web_check_warn "$mode" "pi-web runtime 静态资源缺失：$runtime_dir/.next/static"
    return 1
  fi
  if [ -f "$pi_web_dir/scripts/check.sh" ]; then
    if [ "$mode" = "quiet" ]; then
      (cd "$pi_web_dir" && OPENHOUSE_PI_WEB_RUNTIME_DIR="$runtime_dir" sh scripts/check.sh) >/dev/null 2>&1 || return 1
    else
      (cd "$pi_web_dir" && OPENHOUSE_PI_WEB_RUNTIME_DIR="$runtime_dir" sh scripts/check.sh) || return 1
    fi
  fi
  return 0
}

pid_alive() {
  local pid="$1"
  [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1
}

pid_matches_rescue() {
  local pid="$1"
  [ -n "$pid" ] || return 1
  [ -r "/proc/$pid/environ" ] || return 1
  tr '\000' '\n' < "/proc/$pid/environ" 2>/dev/null \
    | grep -Fx "OPENHOUSE_PI_WEB_RESCUE_MARKER=$rescue_marker" >/dev/null 2>&1
}

find_rescue_pid_by_marker() {
  local pid
  pid="$(find_rescue_pids_by_marker | head -n 1 || true)"
  if [ -n "$pid" ]; then
    printf '%s\n' "$pid"
    return 0
  fi
  return 1
}

find_rescue_pids_by_marker() {
  local proc pid
  local found=1
  for proc in /proc/[0-9]*; do
    [ -d "$proc" ] || continue
    pid="${proc#/proc/}"
    [ "$pid" != "$$" ] || continue
    if pid_alive "$pid" && pid_matches_rescue "$pid"; then
      printf '%s\n' "$pid"
      found=0
    fi
  done
  return "$found"
}

read_pid() {
  if [ -f "$pid_file" ]; then
    tr -d '[:space:]' < "$pid_file" 2>/dev/null || true
  fi
}

read_managed_pid() {
  local pid
  pid="$(read_pid)"
  if pid_alive "$pid"; then
    if pid_matches_rescue "$pid"; then
      printf '%s\n' "$pid"
      return 0
    fi
    warn "pid 文件指向的进程不是 AI 救援进程，忽略：pid=$pid"
  fi
  pid="$(find_rescue_pid_by_marker || true)"
  if [ -n "$pid" ]; then
    printf '%s\n' "$pid" > "$pid_file"
    printf '%s\n' "$pid"
    return 0
  fi
  return 1
}

start_rescue() {
  local start_cmd pid
  mkdir -p "$state_dir" "$log_dir"
  : > "$rescue_log"
  if probe_url "$url"; then
    pid="$(read_managed_pid || true)"
    if [ -n "$pid" ]; then
      log "pi-web 救援入口已可访问：$url pid=$pid"
      return 0
    fi
    warn "端口已有 HTTP 服务响应，但不是 AI 救援启动的 pi-web：$url"
    return 1
  fi
  if read_managed_pid >/dev/null 2>&1; then
    warn "旧救援进程存在但端口未响应，先停止后重启。"
    stop_rescue
  fi
  ensure_pi_web_installed
  start_cmd="$(find_pi_web_start)" || {
    warn "找不到 openhouse-pi-web-start。"
    return 1
  }
  log "启动独立 pi-web 救援进程：$url"
  nohup env \
    HOME="$HOME" \
    PI_CODING_AGENT_DIR="$PI_CODING_AGENT_DIR" \
    PORT="$port" \
    PI_WEB_PORT="$port" \
    HOST="$host" \
    PI_WEB_HOST="$host" \
    HOSTNAME="$host" \
    OPENHOUSE_PI_WEB_RESCUE_MARKER="$rescue_marker" \
    OPENHOUSE_PI_WEB_RESCUE_PORT="$port" \
    OPENHOUSE_PI_WEB_RUNTIME_DIR="$runtime_dir" \
    PATH="$PATH" \
    "$start_cmd" > "$rescue_log" 2>&1 < /dev/null &
  pid="$!"
  printf '%s\n' "$pid" > "$pid_file"

  for _ in $(seq 1 30); do
    if probe_url "$url"; then
      log "pi-web 救援入口已启动：$url"
      return 0
    fi
    if ! pid_alive "$pid"; then
      warn "pi-web 救援进程提前退出。日志：$rescue_log"
      tail -n 80 "$rescue_log" 2>/dev/null || true
      return 1
    fi
    sleep 1
  done

  warn "pi-web 救援入口启动超时。日志：$rescue_log"
  tail -n 80 "$rescue_log" 2>/dev/null || true
  return 1
}

stop_rescue() {
  local pid pids
  pids="$(find_rescue_pids_by_marker || true)"
  if [ -n "$pids" ]; then
    while IFS= read -r pid; do
      [ -n "$pid" ] || continue
      log "停止 pi-web 救援进程：pid=$pid"
      kill "$pid" >/dev/null 2>&1 || true
    done <<EOF
$pids
EOF
    sleep 1
    while IFS= read -r pid; do
      [ -n "$pid" ] || continue
      if pid_alive "$pid"; then
        kill -9 "$pid" >/dev/null 2>&1 || true
      fi
    done <<EOF
$pids
EOF
  else
    pid="$(read_pid)"
    if [ -n "$pid" ] && pid_alive "$pid"; then
      warn "pid 文件指向的进程不是 AI 救援进程，不会停止：pid=$pid"
    fi
    log "没有发现由 AI 救援启动的 pi-web 进程。"
  fi
  rm -f "$pid_file"
}

print_status() {
  local pid alive installed reachable state
  pid="$(read_managed_pid || true)"
  alive=0
  installed=0
  reachable=0
  [ -n "$pid" ] && pid_alive "$pid" && alive=1
  if pi_web_strong_check quiet; then
    installed=1
  fi
  probe_url "$url" && reachable=1
  if [ "$reachable" = "1" ] && [ "$alive" = "1" ]; then
    state="running"
  elif [ "$reachable" = "1" ]; then
    state="reachable_unmanaged"
  elif [ "$alive" = "1" ]; then
    state="starting_or_unhealthy"
  elif [ "$installed" = "1" ]; then
    state="stopped"
  else
    state="missing"
  fi
  log "status state=$state installed=$installed pid=${pid:-none} pid_alive=$alive reachable=$reachable url=$url runtime=$runtime_dir log=$rescue_log"
  if [ "${1:-status}" = "check" ] && [ "$state" != "running" ]; then
    return 1
  fi
  return 0
}

check_rescue() {
  print_status check
  if probe_url "$url" && read_managed_pid >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

case "$action" in
  start|run|repair|restart)
    if [ "$action" = "restart" ]; then
      stop_rescue || true
    fi
    start_rescue
    ;;
  stop)
    stop_rescue
    ;;
  status)
    print_status
    ;;
  check)
    check_rescue
    ;;
  *)
    warn "不支持的 AI 救援动作：$action"
    exit 2
    ;;
esac
OPENHOUSE_PI_WEB_RESCUE
