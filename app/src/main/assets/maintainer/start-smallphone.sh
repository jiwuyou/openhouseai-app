find_smallphoneai_bootstrap() {
  if [ -n "${SMALLPHONEAI_BOOTSTRAP:-}" ] && [ -f "$SMALLPHONEAI_BOOTSTRAP" ]; then
    printf '%s\n' "$SMALLPHONEAI_BOOTSTRAP"
    return 0
  fi

  for candidate in \
    "$HOME/.smallphoneai-bootstrap/bootstrap.sh" \
    "$HOME/smallphoneai-bootstrap/bootstrap.sh" \
    "$HOME/openhouseai-bootstrap/bootstrap.sh" \
    "/data/data/com.termux/files/home/.smallphoneai-bootstrap/bootstrap.sh"; do
    if [ -f "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

bootstrap="$(find_smallphoneai_bootstrap || true)"
if [ -n "$bootstrap" ]; then
  log "正在执行 SmallPhoneAI runtime hook：$bootstrap start"
  run_logged bash "$bootstrap" start
  exit $?
fi

log "未找到 SmallPhoneAI bootstrap.sh，使用 APK 内置启动钩子启动已安装组件。"
require_ubuntu

run_ubuntu_logged bash <<'SMALLPHONEAI_START'
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
}

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
hermes_dir="${SMALLPHONEAI_HERMES_DIR:-$repo_root/hermes}"
bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
sm_url="${SERVICE_MANAGER_URL:-http://$bind}"
smallphone_url="${SMALLPHONEAI_SMALLPHONE_URL:-http://127.0.0.1:22082/}"
smallphone_core_url="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-http://127.0.0.1:22000/}"
cc_url="${SMALLPHONEAI_CC_CONNECT_URL:-http://127.0.0.1:21040/}"
log_dir="${SMALLPHONEAI_LOG_DIR:-$HOME/.smallphoneai/logs}"

export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:$PATH"

find_service_manager() {
  if command -v service-manager >/dev/null 2>&1; then
    command -v service-manager
    return 0
  fi
  for candidate in \
    "$service_manager_dir/service-manager" \
    "$service_manager_dir/target/release/service-manager" \
    "$service_manager_dir/target/debug/service-manager"; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

truthy() {
  case "${1:-}" in
    1|true|TRUE|True|yes|YES|Yes|on|ON|On) return 0 ;;
    *) return 1 ;;
  esac
}

cc_connect_disabled() {
  truthy "${SMALLPHONEAI_CC_CONNECT_DISABLED:-${SMALLPHONEAI_DISABLE_CC_CONNECT:-0}}" \
    || [ -f "$HOME/.smallphoneai/cc-connect.disabled" ] \
    || [ -f "$HOME/.smallphoneai/disable-cc-connect" ] \
    || [ -f "/data/data/com.termux/files/home/.smallphoneai/cc-connect.disabled" ] \
    || [ -f "/data/data/com.termux/files/home/.smallphoneai/disable-cc-connect" ]
}

probe_url() {
  command -v curl >/dev/null 2>&1 || return 1
  curl -fsS --max-time 2 "$1" >/dev/null 2>&1
}

service_manager_ready() {
  probe_url "$sm_url/api/v1/health" || probe_url "$sm_url/"
}

stack_ready() {
  service_manager_ready \
    && probe_url "$smallphone_url" \
    && probe_url "$smallphone_core_url" \
    && { cc_connect_disabled || probe_url "$cc_url"; }
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

service_manager_listen_addr() {
  local value="$sm_url"
  case "$value" in
    http://*) value="${value#http://}" ;;
    https://*) value="${value#https://}" ;;
    "") value="$bind" ;;
  esac
  value="${value%%/*}"
  [ -n "$value" ] || value="$bind"
  printf '%s' "$value"
}

write_openhouse_service_manager_config() {
  local target="$1"
  local token="$2"
  local listen_addr="$3"
  local dir
  local tmp
  local token_json
  local listen_json

  case "$target" in
    */.config/service-manager/config.json)
      warn "拒绝写入旧 service-manager 配置路径：$target"
      return 1
      ;;
  esac

  dir="$(dirname "$target")"
  if ! mkdir -p "$dir"; then
    warn "无法创建 OpenHouse service-manager 配置目录：$dir"
    return 1
  fi

  token_json="$(json_escape "$token")"
  listen_json="$(json_escape "$listen_addr")"
  tmp="$target.tmp.$$"
  if ! cat > "$tmp" <<EOF
{
  "auth_token": "$token_json",
  "listen_addr": "$listen_json"
}
EOF
  then
    warn "无法写入 OpenHouse service-manager 临时配置：$tmp"
    rm -f "$tmp" >/dev/null 2>&1 || true
    return 1
  fi

  chmod 600 "$tmp" >/dev/null 2>&1 || true
  if ! mv "$tmp" "$target"; then
    warn "无法更新 OpenHouse service-manager 配置：$target"
    rm -f "$tmp" >/dev/null 2>&1 || true
    return 1
  fi
}

sync_openhouse_service_manager_config() {
  local token="$1"
  local listen_addr="${2:-}"
  local target
  local wrote=0
  local failed=0

  if [ -z "$token" ]; then
    warn "service-manager token 为空，跳过同步到 OpenHouse 专用配置。"
    return 1
  fi
  [ -n "$listen_addr" ] || listen_addr="$(service_manager_listen_addr)"

  for target in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json" \
    "$HOME/.config/openhouseai/service-manager/config.json"; do
    [ -n "$target" ] || continue
    case "$target" in
      /data/data/com.termux/files/home/*)
        [ -d "/data/data/com.termux/files/home" ] || continue
        ;;
    esac
    if write_openhouse_service_manager_config "$target" "$token" "$listen_addr"; then
      wrote=1
    else
      failed=1
    fi
  done

  if [ "$wrote" = "1" ]; then
    log "已同步 service-manager token 到 OpenHouse 专用配置：listen_addr=$listen_addr"
  else
    warn "未能同步 service-manager token 到任何 OpenHouse 专用配置路径。"
  fi
  [ "$failed" = "0" ] || return 1
}

register_if_present() {
  local label="$1"
  local dir="$2"
  local script="$dir/scripts/register-service.sh"
  if [ -f "$script" ]; then
    chmod +x "$script"
    log "$label: 刷新 service-manager 注册。"
    (cd "$dir" && ./scripts/register-service.sh) || warn "$label: register-service.sh 执行失败，继续尝试启动已注册服务。"
  else
    warn "$label: 缺少注册入口，跳过：$script"
  fi
}

service_manager_bin="$(find_service_manager || true)"
if [ -z "$service_manager_bin" ]; then
  warn "找不到 service-manager。请先在维护中心执行 SmallPhoneAI 修复或完整安装。"
  exit 2
fi

export PATH="$(dirname "$service_manager_bin"):$PATH"
mkdir -p "$log_dir"

if service_manager_ready; then
  log "service-manager 已可访问：$sm_url"
else
  log "正在启动 service-manager：$bind"
  nohup "$service_manager_bin" serve --bind "$bind" > "$log_dir/service-manager.log" 2>&1 < /dev/null &
  for _ in $(seq 1 30); do
    service_manager_ready && break
    sleep 1
  done
fi

if ! service_manager_ready; then
  warn "service-manager 未能启动。日志：$log_dir/service-manager.log"
  [ -f "$log_dir/service-manager.log" ] && tail -n 80 "$log_dir/service-manager.log" >&2 || true
  exit 1
fi

register_if_present "cc-connect/openhouse-connect" "$cc_connect_dir"
register_if_present "SmallPhone" "$smallphone_dir"
register_if_present "Hermes" "$hermes_dir"

if ! command -v curl >/dev/null 2>&1; then
  warn "缺少 curl，无法调用 service-manager。"
  exit 1
fi

sm_token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
if [ -z "$sm_token" ]; then
  sm_token="$("$service_manager_bin" token show 2>/dev/null | tr -d '\r\n' || true)"
fi
if [ -z "$sm_token" ]; then
  warn "无法获取 service-manager token，无法启动 group:local-stack。"
  exit 1
fi
sync_openhouse_service_manager_config "$sm_token" "$(service_manager_listen_addr)" || true

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-start.XXXXXX")"
cleanup() {
  rm -rf "$work_dir" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT HUP TERM
printf 'header = "Authorization: Bearer %s"\n' "$sm_token" > "$work_dir/curl.cfg"

log "正在通过 service-manager 启动 group:local-stack。"
curl -q -fsS --max-time 10 -X POST -K "$work_dir/curl.cfg" "$sm_url/api/v1/groups/local-stack/start" >/dev/null

for _ in $(seq 1 45); do
  if stack_ready; then
    log "SmallPhoneAI 运行栈已就绪：SmallPhone=$smallphone_url service-manager=$sm_url cc-connect=$cc_url"
    exit 0
  fi
  sleep 1
done

warn "SmallPhoneAI 运行栈启动后仍未完全就绪。"
printf 'service-manager=%s\nSmallPhone=%s\nSmallPhone Core=%s\ncc-connect=%s\n' \
  "$(service_manager_ready && printf reachable || printf down)" \
  "$(probe_url "$smallphone_url" && printf reachable || printf down)" \
  "$(probe_url "$smallphone_core_url" && printf reachable || printf down)" \
  "$(cc_connect_disabled && printf disabled || { probe_url "$cc_url" && printf reachable || printf down; })"
exit 1
SMALLPHONEAI_START
