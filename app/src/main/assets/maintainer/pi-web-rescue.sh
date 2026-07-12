set -euo pipefail

rescue_warn() {
  printf '[OpenHouse AI Rescue] WARN: %s\n' "$*" >&2
}

action="${OPENHOUSE_PI_WEB_RESCUE_ACTION:-status}"
port="${OPENHOUSE_PI_WEB_RESCUE_PORT:-__PORT__}"
case "$port" in
  ''|*[!0-9]*)
    rescue_warn "AI 救援端口无效：$port"
    exit 2
    ;;
esac
if [ "$port" -lt 1024 ] || [ "$port" -gt 65535 ]; then
  rescue_warn "AI 救援端口超出范围：$port（允许 1024-65535）"
  exit 2
fi

# AI rescue is deliberately a Termux-native control path. It must remain
# usable when Ubuntu/proot or service-manager is unavailable.
export HOME="${HOME:-/data/data/com.termux/files/home}"
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PATH="$PREFIX/bin:$HOME/.local/node/bin:$HOME/.local/bin:$HOME/.npm-global/bin:/system/bin:${PATH:-}"
export PI_CODING_AGENT_DIR="${PI_CODING_AGENT_DIR:-$HOME/.pi}"
export OPENHOUSE_PI_WEB_DEFAULT_CWD="${OPENHOUSE_PI_WEB_DEFAULT_CWD:-$HOME}"
export OPENHOUSE_PI_WEB_RUNTIME_DIR="${OPENHOUSE_PI_WEB_RUNTIME_DIR:-$HOME/.local/share/openhouseai/pi-web}"

host="127.0.0.1"
url="http://$host:$port/"
payload_root="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}"
pi_web_dir_is_explicit=0
if [ -n "${OPENHOUSE_PI_WEB_DIR+x}" ] || [ -n "${SMALLPHONEAI_PI_WEB_DIR+x}" ]; then
  pi_web_dir_is_explicit=1
fi
pi_web_dir="${OPENHOUSE_PI_WEB_DIR:-${SMALLPHONEAI_PI_WEB_DIR:-$HOME/.local/share/openhouseai/pi-web-bundle}}"
runtime_dir="$OPENHOUSE_PI_WEB_RUNTIME_DIR"
state_dir="$HOME/.smallphoneai/rescue"
log_dir="$HOME/.smallphoneai/logs"
pid_file="$state_dir/pi-web-$port.pid"
marker_file="$state_dir/pi-web-$port.marker"
rescue_log="$log_dir/pi-web-rescue-$port.log"
rescue_marker="openhouse-pi-web-rescue:$port:$runtime_dir"
payload_stamp_name=".openhouse-payload.cksum"

log "AI 救援：在 Termux native 环境独立控制原始 pi-web，动作=$action，端口=$port。"

probe_url() {
  if command -v curl >/dev/null 2>&1; then
    curl -sS -o /dev/null --connect-timeout 1 --max-time 2 "$url" >/dev/null 2>&1
    return $?
  fi
  command -v node >/dev/null 2>&1 || return 1
  node -e '
const http = require("http");
const request = http.get(process.argv[1], (response) => {
  response.resume();
  response.once("end", () => process.exit(0));
});
request.setTimeout(2000, () => request.destroy());
request.once("error", () => process.exit(1));
' "$url" >/dev/null 2>&1
}

loopback_port_in_use() {
  command -v node >/dev/null 2>&1 || return 1
  node -e '
const net = require("net");
const server = net.createServer();
server.unref();
server.once("error", (error) => process.exit(error.code === "EADDRINUSE" ? 0 : 2));
server.listen(Number(process.argv[1]), "127.0.0.1", () => server.close(() => process.exit(1)));
' "$port" >/dev/null 2>&1
}

find_pi_web_start() {
  if [ -x "$pi_web_dir/bin/openhouse-pi-web-start" ]; then
    printf '%s\n' "$pi_web_dir/bin/openhouse-pi-web-start"
    return 0
  fi
  if [ -x "$HOME/.local/bin/openhouse-pi-web-start" ]; then
    printf '%s\n' "$HOME/.local/bin/openhouse-pi-web-start"
    return 0
  fi
  if command -v openhouse-pi-web-start >/dev/null 2>&1; then
    command -v openhouse-pi-web-start
    return 0
  fi
  return 1
}

read_payload_stamp() {
  local payload="$payload_root/pi-web.tar"
  [ -f "$payload" ] || return 1
  cksum "$payload" | awk '{print $1 ":" $2}'
}

installed_payload_is_current() {
  local expected bundle_stamp runtime_stamp
  [ "$pi_web_dir_is_explicit" = "0" ] || return 0
  expected="$(read_payload_stamp || true)"
  # If the synchronized APK payload is temporarily unavailable, retain a
  # complete installed runtime instead of treating it as stale.
  [ -n "$expected" ] || return 0
  bundle_stamp="$(cat "$pi_web_dir/$payload_stamp_name" 2>/dev/null || true)"
  runtime_stamp="$(cat "$runtime_dir/$payload_stamp_name" 2>/dev/null || true)"
  [ "$bundle_stamp" = "$expected" ] && [ "$runtime_stamp" = "$expected" ]
}

extract_pi_web_payload_if_missing() {
  local payload="$payload_root/pi-web.tar"
  local work_dir payload_dir payload_stamp
  if [ -x "$pi_web_dir/bin/openhouse-pi-web-start" ] \
    && [ -f "$pi_web_dir/runtime/pi-web/server.js" ]; then
    if [ "$pi_web_dir_is_explicit" = "1" ]; then
      return 0
    fi
    payload_stamp="$(read_payload_stamp || true)"
    if [ -z "$payload_stamp" ] \
      || [ "$(cat "$pi_web_dir/$payload_stamp_name" 2>/dev/null || true)" = "$payload_stamp" ]; then
      return 0
    fi
  fi
  if [ ! -f "$payload" ]; then
    rescue_warn "pi-web payload 不存在：$payload"
    return 1
  fi
  log "从 APK payload 准备 Termux-native pi-web：$payload"
  work_dir="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/openhouse-pi-web-rescue.XXXXXX")"
  tar -xf "$payload" -C "$work_dir"
  if [ -x "$work_dir/bin/openhouse-pi-web-start" ] \
    && [ -f "$work_dir/runtime/pi-web/server.js" ]; then
    payload_dir="$work_dir"
  else
    payload_dir="$(find "$work_dir" -mindepth 2 -maxdepth 4 \
      -path '*/bin/openhouse-pi-web-start' -type f -print \
      | sed 's#/bin/openhouse-pi-web-start$##' | head -n 1)"
  fi
  if [ -z "${payload_dir:-}" ] || [ ! -f "$payload_dir/runtime/pi-web/server.js" ]; then
    rm -rf "$work_dir"
    rescue_warn "无法识别 pi-web payload。"
    return 1
  fi
  mkdir -p "$(dirname "$pi_web_dir")"
  rm -rf "$pi_web_dir.tmp.$$"
  mkdir -p "$pi_web_dir.tmp.$$"
  cp -a "$payload_dir/." "$pi_web_dir.tmp.$$/"
  payload_stamp="$(read_payload_stamp)"
  printf '%s\n' "$payload_stamp" > "$pi_web_dir.tmp.$$/$payload_stamp_name"
  rm -rf "$pi_web_dir"
  mv "$pi_web_dir.tmp.$$" "$pi_web_dir"
  rm -rf "$work_dir"
}

install_pi_web_bundle_native() {
  local runtime_src="$pi_web_dir/runtime/pi-web"
  local runtime_tmp="$runtime_dir.tmp.$$"
  local payload_stamp
  [ -f "$runtime_src/server.js" ] || {
    rescue_warn "payload 中缺少 pi-web standalone server：$runtime_src/server.js"
    return 1
  }
  [ -d "$runtime_src/node_modules" ] || {
    rescue_warn "payload 中缺少 pi-web node_modules：$runtime_src/node_modules"
    return 1
  }
  [ -d "$runtime_src/.next/static" ] || {
    rescue_warn "payload 中缺少 pi-web 静态资源：$runtime_src/.next/static"
    return 1
  }

  log "安装 pi-web standalone runtime 到 Termux：$runtime_dir"
  mkdir -p "$(dirname "$runtime_dir")" "$HOME/.local/bin" "$PI_CODING_AGENT_DIR" "$OPENHOUSE_PI_WEB_DEFAULT_CWD"
  rm -rf "$runtime_tmp"
  mkdir -p "$runtime_tmp"
  (cd "$runtime_src" && tar -cf - .) | (cd "$runtime_tmp" && tar -xf -)
  payload_stamp="$(cat "$pi_web_dir/$payload_stamp_name" 2>/dev/null || true)"
  if [ -n "$payload_stamp" ]; then
    printf '%s\n' "$payload_stamp" > "$runtime_tmp/$payload_stamp_name"
  fi
  rm -rf "$runtime_dir"
  mv "$runtime_tmp" "$runtime_dir"
  install -m 755 "$pi_web_dir/bin/openhouse-pi-web-start" "$HOME/.local/bin/openhouse-pi-web-start"
}

pi_web_check_warn() {
  local mode="$1"
  shift
  if [ "$mode" != "quiet" ]; then
    rescue_warn "$*"
  fi
}

pi_web_strong_check() {
  local mode="${1:-quiet}"
  local start_cmd
  if ! command -v node >/dev/null 2>&1; then
    pi_web_check_warn "$mode" "Termux Node.js 不可用，无法启动 pi-web。"
    return 1
  fi
  if ! node -e '
const [major, minor] = process.versions.node.split(".").map(Number);
process.exit(major > 22 || (major === 22 && minor >= 19) ? 0 : 1);
' >/dev/null 2>&1; then
    pi_web_check_warn "$mode" "pi-web 需要 Termux Node.js >= 22.19。"
    return 1
  fi
  start_cmd="$(find_pi_web_start || true)"
  if [ -z "$start_cmd" ] || [ ! -x "$start_cmd" ]; then
    pi_web_check_warn "$mode" "Termux openhouse-pi-web-start 不存在或不可执行。"
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
  return 0
}

ensure_pi_web_installed() {
  if pi_web_strong_check quiet && installed_payload_is_current; then
    return 0
  fi
  pi_web_strong_check verbose || true
  extract_pi_web_payload_if_missing || return 1
  install_pi_web_bundle_native || return 1
  pi_web_strong_check verbose || return 1
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

find_rescue_pid_by_marker() {
  local pid
  pid="$(find_rescue_pids_by_marker | head -n 1 || true)"
  [ -n "$pid" ] || return 1
  printf '%s\n' "$pid"
}

read_pid() {
  if [ -f "$pid_file" ]; then
    tr -d '[:space:]' < "$pid_file" 2>/dev/null || true
  fi
}

write_instance_state() {
  local pid="$1"
  printf '%s\n' "$pid" > "$pid_file"
  {
    printf 'marker=%s\n' "$rescue_marker"
    printf 'pid=%s\n' "$pid"
    printf 'url=%s\n' "$url"
    printf 'runtime=%s\n' "$runtime_dir"
  } > "$marker_file"
}

read_managed_pid() {
  local pid
  pid="$(read_pid)"
  if pid_alive "$pid"; then
    if pid_matches_rescue "$pid"; then
      printf '%s\n' "$pid"
      return 0
    fi
    rescue_warn "pid 文件指向的进程不是当前端口的 AI 救援进程，忽略：pid=$pid"
  fi
  pid="$(find_rescue_pid_by_marker || true)"
  if [ -n "$pid" ]; then
    write_instance_state "$pid"
    printf '%s\n' "$pid"
    return 0
  fi
  return 1
}

stop_rescue() {
  local pid pids
  pids="$(find_rescue_pids_by_marker || true)"
  if [ -n "$pids" ]; then
    while IFS= read -r pid; do
      [ -n "$pid" ] || continue
      log "停止 Termux-native pi-web 救援进程：pid=$pid port=$port"
      kill "$pid" >/dev/null 2>&1 || true
    done <<EOF
$pids
EOF
    sleep 1
    while IFS= read -r pid; do
      [ -n "$pid" ] || continue
      if pid_alive "$pid" && pid_matches_rescue "$pid"; then
        kill -9 "$pid" >/dev/null 2>&1 || true
      fi
    done <<EOF
$pids
EOF
  else
    pid="$(read_pid)"
    if [ -n "$pid" ] && pid_alive "$pid"; then
      rescue_warn "pid 文件指向的进程不是当前端口的 AI 救援进程，不会停止：pid=$pid"
    fi
    log "没有发现当前端口由 AI 救援启动的 pi-web 进程。"
  fi
  rm -f "$pid_file" "$marker_file"
}

start_rescue() {
  local start_cmd pid
  mkdir -p "$state_dir" "$log_dir" "$PI_CODING_AGENT_DIR" "$OPENHOUSE_PI_WEB_DEFAULT_CWD"
  if probe_url; then
    pid="$(read_managed_pid || true)"
    if [ -n "$pid" ]; then
      log "Termux-native pi-web 救援入口已可访问：$url pid=$pid"
      return 0
    fi
    rescue_warn "端口已有 HTTP 服务响应，但不是当前端口的 AI 救援 pi-web：$url"
    return 1
  fi
  if read_managed_pid >/dev/null 2>&1; then
    rescue_warn "旧救援进程存在但端口未响应，先停止后重启。"
    stop_rescue
  fi
  ensure_pi_web_installed || return 1
  if loopback_port_in_use; then
    rescue_warn "loopback 端口已被其他进程占用，不会启动或停止该进程：$host:$port"
    return 1
  fi
  start_cmd="$(find_pi_web_start)" || {
    rescue_warn "找不到 Termux-native openhouse-pi-web-start。"
    return 1
  }
  : > "$rescue_log"
  log "启动 Termux-native 原始 pi-web 救援进程：$url"
  nohup env \
    HOME="$HOME" \
    PREFIX="$PREFIX" \
    PATH="$PATH" \
    PI_CODING_AGENT_DIR="$PI_CODING_AGENT_DIR" \
    OPENHOUSE_PI_WEB_DEFAULT_CWD="$OPENHOUSE_PI_WEB_DEFAULT_CWD" \
    OPENHOUSE_PI_WEB_RUNTIME_DIR="$runtime_dir" \
    OPENHOUSE_PI_WEB_RESCUE_MARKER="$rescue_marker" \
    OPENHOUSE_PI_WEB_RESCUE_PORT="$port" \
    PORT="$port" \
    PI_WEB_PORT="$port" \
    HOST="$host" \
    PI_WEB_HOST="$host" \
    HOSTNAME="$host" \
    "$start_cmd" > "$rescue_log" 2>&1 < /dev/null &
  pid="$!"
  write_instance_state "$pid"

  for _ in $(seq 1 30); do
    if probe_url; then
      log "Termux-native pi-web 救援入口已启动：$url pid=$pid"
      return 0
    fi
    if ! pid_alive "$pid"; then
      rescue_warn "pi-web 救援进程提前退出。日志：$rescue_log"
      tail -n 80 "$rescue_log" 2>/dev/null || true
      rm -f "$pid_file" "$marker_file"
      return 1
    fi
    sleep 1
  done

  rescue_warn "pi-web 救援入口启动超时。日志：$rescue_log"
  tail -n 80 "$rescue_log" 2>/dev/null || true
  return 1
}

print_status() {
  local pid alive installed reachable state
  pid="$(read_managed_pid || true)"
  alive=0
  installed=0
  reachable=0
  [ -n "$pid" ] && pid_alive "$pid" && alive=1
  pi_web_strong_check quiet && installed=1
  probe_url && reachable=1
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
  log "status state=$state installed=$installed pid=${pid:-none} pid_alive=$alive reachable=$reachable url=$url runtime=$runtime_dir log=$rescue_log marker=$marker_file"
  if [ "${1:-status}" = "check" ] && [ "$state" != "running" ]; then
    return 1
  fi
}

case "$action" in
  start|run|repair)
    start_rescue
    ;;
  restart)
    stop_rescue || true
    start_rescue
    ;;
  stop)
    stop_rescue
    ;;
  status)
    print_status
    ;;
  check)
    print_status check
    ;;
  *)
    rescue_warn "不支持的 AI 救援动作：$action"
    exit 2
    ;;
esac
