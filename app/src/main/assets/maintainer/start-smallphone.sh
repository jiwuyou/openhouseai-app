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

find_termux_canonical_service_manager_config() {
  local candidate
  for candidate in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json"; do
    [ -n "$candidate" ] && [ -f "$candidate" ] || continue
    case "$candidate" in
      */.config/service-manager/config.json) continue ;;
    esac
    printf '%s\n' "$candidate"
    return 0
  done
  return 1
}

read_termux_service_manager_config_value() {
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

normalize_termux_service_manager_bind() {
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

termux_service_manager_bind_from_config() {
  local config="$1" endpoint
  endpoint="$(read_termux_service_manager_config_value "$config" \
    listen_addr listenAddr bind bind_addr bindAddr base_url baseUrl baseURL url || true)"
  normalize_termux_service_manager_bind "${endpoint:-127.0.0.1:20087}"
}

termux_service_manager_url_from_config() {
  local config="$1" endpoint scheme bind
  endpoint="$(read_termux_service_manager_config_value "$config" \
    listen_addr listenAddr bind bind_addr bindAddr base_url baseUrl baseURL url || true)"
  case "$endpoint" in
    https://*) scheme=https ;;
    *) scheme=http ;;
  esac
  bind="$(normalize_termux_service_manager_bind "${endpoint:-127.0.0.1:20087}")" || return 1
  printf '%s://%s\n' "$scheme" "$bind"
}

find_installed_termux_service_manager() {
  local candidate
  for candidate in \
    "$(command -v service-manager 2>/dev/null || true)" \
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/service-manager" \
    "${HOME:+$HOME/.local/bin/service-manager}"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    printf '%s\n' "$candidate"
    return 0
  done
  return 1
}

termux_service_manager_health_ready() {
  local url="$1"
  command -v curl >/dev/null 2>&1 || return 1
  curl -q -fsS --max-time 2 "$url/api/v1/health" >/dev/null 2>&1
}

termux_service_manager_auth_ready() (
  local config="$1" url="$2" token work_dir curl_cfg escaped_token
  token="$(read_termux_service_manager_config_value "$config" auth_token authToken || true)"
  [ -n "$token" ] || return 1
  command -v curl >/dev/null 2>&1 || return 1

  mkdir -p "${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}"
  work_dir="$(mktemp -d "${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}/openhouse-sm-auth.XXXXXX")"
  trap 'rm -rf "$work_dir" >/dev/null 2>&1 || true' EXIT INT HUP TERM
  chmod 700 "$work_dir" >/dev/null 2>&1 || true
  curl_cfg="$work_dir/curl.cfg"
  escaped_token="$(printf '%s' "$token" | sed 's/\\/\\\\/g; s/"/\\"/g')"
  umask 077
  printf 'header = "Authorization: Bearer %s"\n' "$escaped_token" > "$curl_cfg"
  curl -q -fsS --max-time 3 -K "$curl_cfg" "$url/api/v1/services" >/dev/null 2>&1
)

termux_service_manager_serve_pids() {
  local proc comm args
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -f '(^|/)service-manager[[:space:]]+serve([[:space:]]|$)' 2>/dev/null || true
    return 0
  fi
  for proc in /proc/[0-9]*; do
    [ -r "$proc/comm" ] && [ -r "$proc/cmdline" ] || continue
    comm="$(cat "$proc/comm" 2>/dev/null || true)"
    [ "$comm" = "service-manager" ] || continue
    args="$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null || true)"
    printf '%s\n' "$args" | grep -Fqx -- serve || continue
    printf '%s\n' "${proc##*/}"
  done
}

termux_service_manager_port_open() {
  local bind="$1" host port
  host="${bind%:*}"
  port="${bind##*:}"
  [ -n "$host" ] && [ -n "$port" ] && [ "$host" != "$port" ] || return 1
  bash -c 'exec 3<>"/dev/tcp/$1/$2"' bash "$host" "$port" >/dev/null 2>&1
}

wait_for_termux_service_manager() {
  local url="$1" attempts="${2:-30}"
  local index=1
  while [ "$index" -le "$attempts" ]; do
    termux_service_manager_health_ready "$url" && return 0
    sleep 1
    index=$((index + 1))
  done
  return 1
}

ensure_termux_native_service_manager() {
  local config bind url token binary log_dir log_file bootstrap_log existing_pids started_pid=""

  config="$(find_termux_canonical_service_manager_config || true)"
  if [ -z "$config" ]; then
    log "未找到 OpenHouse canonical service-manager 配置；拒绝使用 ~/.config/service-manager/config.json。"
    return 1
  fi
  token="$(read_termux_service_manager_config_value "$config" auth_token authToken || true)"
  if [ -z "$token" ]; then
    log "OpenHouse canonical service-manager 配置缺少 auth_token：$config"
    return 1
  fi
  bind="$(termux_service_manager_bind_from_config "$config")" || {
    log "OpenHouse canonical service-manager bind 无效：$config"
    return 1
  }
  url="$(termux_service_manager_url_from_config "$config")" || return 1

  export SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG="$config"
  export SERVICE_MANAGER_URL="$url"
  export SMALLPHONEAI_SERVICE_MANAGER_BIND="$bind"

  if termux_service_manager_health_ready "$url"; then
    if ! termux_service_manager_auth_ready "$config" "$url"; then
      log "service-manager API 可达，但与 OpenHouse canonical config 的认证不匹配；拒绝启动第二实例。"
      return 1
    fi
    log "Termux native service-manager 已可访问并通过 canonical config 认证：$url"
    return 0
  fi

  existing_pids="$(termux_service_manager_serve_pids | tr '\n' ' ' | sed 's/[[:space:]]*$//' || true)"
  if [ -n "$existing_pids" ]; then
    log "检测到已有 service-manager serve 进程，等待 canonical API 就绪：pids=$existing_pids"
    if wait_for_termux_service_manager "$url" 10 \
      && termux_service_manager_auth_ready "$config" "$url"; then
      return 0
    fi
    log "已有 service-manager 进程未能提供 canonical API；拒绝另起第二实例。"
    return 1
  fi

  if termux_service_manager_port_open "$bind"; then
    log "service-manager canonical 端口已被非预期进程占用：$bind"
    return 1
  fi

  binary="$(find_installed_termux_service_manager || true)"
  if [ -z "$binary" ]; then
    log "未找到已安装的 Termux native service-manager 二进制。"
    return 1
  fi

  log_dir="${SMALLPHONEAI_LOG_DIR:-$HOME/.smallphoneai/logs}"
  log_file="$log_dir/service-manager.log"
  bootstrap_log="$log_dir/service-manager-bootstrap.log"
  umask 077
  mkdir -p "$log_dir"
  : > "$bootstrap_log"
  chmod 600 "$bootstrap_log" >/dev/null 2>&1 || true
  log "正在使用 OpenHouse canonical config 启动 Termux native service-manager：bind=$bind"
  nohup env \
    -u SERVICE_MANAGER_TOKEN \
    -u SMALLPHONE_SERVICE_MANAGER_TOKEN \
    "$binary" serve --config "$config" --bind "$bind" --log-file "$log_file" </dev/null > "$bootstrap_log" 2>&1 &
  started_pid=$!

  if ! wait_for_termux_service_manager "$url" "${SMALLPHONEAI_SERVICE_MANAGER_READY_ATTEMPTS:-30}"; then
    kill "$started_pid" >/dev/null 2>&1 || true
    log "Termux native service-manager 在有限等待时间内未就绪；正式日志：$log_file；启动日志：$bootstrap_log"
    return 1
  fi
  if ! termux_service_manager_auth_ready "$config" "$url"; then
    kill "$started_pid" >/dev/null 2>&1 || true
    log "新启动的 service-manager 与 canonical config 认证不匹配，已停止该实例。"
    return 1
  fi

  log "Termux native service-manager 已启动并通过 canonical config 认证：$url"
}

ensure_termux_native_service_manager || exit 1

bootstrap="$(find_smallphoneai_bootstrap || true)"
if [ -n "$bootstrap" ]; then
  log "正在执行 SmallPhoneAI runtime hook：$bootstrap start"
  run_logged env \
    -u SERVICE_MANAGER_TOKEN \
    -u SMALLPHONE_SERVICE_MANAGER_TOKEN \
    OPENHOUSE_PI_RUNTIME="${OPENHOUSE_PI_RUNTIME:-termux}" \
    SMALLPHONEAI_PI_RUNTIME="${SMALLPHONEAI_PI_RUNTIME:-termux}" \
    OPENHOUSE_PI_NODE_RUNTIME="${OPENHOUSE_PI_NODE_RUNTIME:-termux}" \
    SMALLPHONEAI_START_TARGETS="${SMALLPHONEAI_START_TARGETS:-pi-agent,pi-web}" \
    bash "$bootstrap" start
  exit $?
fi

log "未找到 SmallPhoneAI bootstrap.sh，使用 APK 内置启动钩子启动已安装组件。"
export SMALLPHONEAI_START_TARGETS="${SMALLPHONEAI_START_TARGETS:-pi-agent,pi-web}"
require_ubuntu

run_ubuntu_logged env \
  -u SERVICE_MANAGER_TOKEN \
  -u SMALLPHONE_SERVICE_MANAGER_TOKEN \
  SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG="$SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG" \
  SERVICE_MANAGER_URL="$SERVICE_MANAGER_URL" \
  SMALLPHONEAI_SERVICE_MANAGER_BIND="$SMALLPHONEAI_SERVICE_MANAGER_BIND" \
  bash <<'SMALLPHONEAI_START'
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
}

read_openhouse_service_manager_endpoint() {
  local config key value
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json"; do
    [ -n "$config" ] && [ -f "$config" ] || continue
    for key in listen_addr listenAddr base_url baseUrl baseURL url; do
      value="$(sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$config" | head -n 1 || true)"
      if [ -n "$value" ]; then
        printf '%s\n' "$value"
        return 0
      fi
    done
  done
  return 1
}

read_openhouse_service_manager_token() {
  local config token
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json"; do
    [ -n "$config" ] && [ -f "$config" ] || continue
    token="$(sed -n 's/.*"auth_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$config" | head -n 1 || true)"
    if [ -n "$token" ]; then
      printf '%s\n' "$token"
      return 0
    fi
  done
  return 1
}

normalize_service_manager_bind() {
  local value="${1:-}"
  case "$value" in
    http://*) value="${value#http://}" ;;
    https://*) value="${value#https://}" ;;
  esac
  value="${value%%/*}"
  case "$value" in
    "") return 1 ;;
    :*) printf '127.0.0.1%s\n' "$value"; return 0 ;;
    0.0.0.0) printf '127.0.0.1\n'; return 0 ;;
    0.0.0.0:*) printf '127.0.0.1:%s\n' "${value#0.0.0.0:}"; return 0 ;;
    "::"|"[::]") printf '127.0.0.1\n'; return 0 ;;
    "[::]:"*) printf '127.0.0.1:%s\n' "${value#"[::]:"}"; return 0 ;;
    :::*) printf '127.0.0.1:%s\n' "${value#:::}"; return 0 ;;
    *[!0-9]*) printf '%s\n' "$value"; return 0 ;;
    *) printf '127.0.0.1:%s\n' "$value"; return 0 ;;
  esac
}

configured_service_manager_bind() {
  local endpoint
  endpoint="$(read_openhouse_service_manager_endpoint || true)"
  if [ -n "$endpoint" ] && normalize_service_manager_bind "$endpoint"; then
    return
  fi
  if [ -n "${SERVICE_MANAGER_URL:-}" ] && normalize_service_manager_bind "$SERVICE_MANAGER_URL"; then
    return
  fi
  if [ -n "${SMALLPHONEAI_SERVICE_MANAGER_BIND:-}" ]; then
    normalize_service_manager_bind "$SMALLPHONEAI_SERVICE_MANAGER_BIND"
    return
  fi
  printf '127.0.0.1:20087\n'
}

configured_service_manager_url() {
  local endpoint scheme bind
  endpoint="$(read_openhouse_service_manager_endpoint || true)"
  if [ -z "$endpoint" ]; then
    endpoint="${SERVICE_MANAGER_URL:-}"
  fi
  if [ -z "$endpoint" ] && [ -n "${SMALLPHONEAI_SERVICE_MANAGER_BIND:-}" ]; then
    endpoint="$SMALLPHONEAI_SERVICE_MANAGER_BIND"
  fi
  case "$endpoint" in
    https://*) scheme="https" ;;
    *) scheme="http" ;;
  esac
  bind="$(normalize_service_manager_bind "${endpoint:-$(configured_service_manager_bind)}")" || bind="127.0.0.1:20087"
  printf '%s://%s\n' "$scheme" "$bind"
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
pi_agent_dir="${OPENHOUSE_PI_AGENT_DIR:-${SMALLPHONEAI_PI_AGENT_DIR:-$(default_path /root/projects/pi pi-runtime)}}"
pi_web_dir="${OPENHOUSE_PI_WEB_DIR:-${SMALLPHONEAI_PI_WEB_DIR:-$(default_path /root/projects/pi-web pi-web)}}"
bind="$(configured_service_manager_bind)"
sm_url="$(configured_service_manager_url)"
smallphone_url="${SMALLPHONEAI_SMALLPHONE_URL:-http://127.0.0.1:22082/}"
smallphone_core_url="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-http://127.0.0.1:22000/}"
pi_web_url="${OPENHOUSE_PI_WEB_URL:-${PI_WEB_URL:-http://127.0.0.1:30141/}}"
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

stack_ready() {
  service_manager_ready || return 1
  if start_target_requested "pi-web" && ! probe_url "$pi_web_url"; then
    return 1
  fi
  if start_target_requested "smallphone" && ! probe_url "$smallphone_url"; then
    return 1
  fi
  if start_target_requested "smallphone-core" && ! probe_url "$smallphone_core_url"; then
    return 1
  fi
  return 0
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

ensure_openhouse_system_layout() {
  local root
  for root in \
    "$HOME" \
    "${SMALLPHONEAI_TERMUX_HOME:-}" \
    "/data/data/com.termux/files/home"; do
    [ -n "$root" ] && [ -d "$root" ] || continue
    mkdir -p \
      "$root/.config/openhouseai/subjects.d" \
      "$root/.config/openhouseai/system" \
      "$root/.local/state/openhouseai/checks/last" || true
  done
}

write_openhouse_service_manager_config() {
  local target="$1"
  local token="$2"
  local listen_addr="$3"
  local dir
  local tmp
  local token_json
  local listen_json
  local config_home
  local log_path
  local log_path_json

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
  config_home="${target%/.config/openhouseai/service-manager/config.json}"
  log_path="$config_home/.smallphoneai/logs/service-manager.log"
  log_path_json="$(json_escape "$log_path")"
  tmp="$target.tmp.$$"
  if ! cat > "$tmp" <<EOF
{
  "auth_token": "$token_json",
  "listen_addr": "$listen_json",
  "logging": {
    "path": "$log_path_json",
    "max_bytes": 16777216,
    "retain_files": 2
  }
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

ensure_openhouse_system_layout

service_manager_bin="$(find_service_manager || true)"
if [ -z "$service_manager_bin" ]; then
  warn "当前 Ubuntu 环境未找到 service-manager 二进制；将只连接 Termux native 控制中枢。"
else
  export PATH="$(dirname "$service_manager_bin"):$PATH"
fi

mkdir -p "$log_dir"

if service_manager_ready; then
  log "service-manager 已可访问：$sm_url"
else
  warn "Termux native service-manager 不可访问：$sm_url。请先在运行控制中执行“修复控制中枢”。"
  exit 1
fi

start_target_requested "cc-connect" && register_if_present "cc-connect/openhouse-connect" "$cc_connect_dir"
start_target_requested "smallphone" && register_if_present "SmallPhone" "$smallphone_dir"
start_target_requested "pi-agent" && register_if_present "pi-agent" "$pi_agent_dir"
start_target_requested "pi-web" && register_if_present "pi-web" "$pi_web_dir"

if ! command -v curl >/dev/null 2>&1; then
  warn "缺少 curl，无法调用 service-manager。"
  exit 1
fi

sm_token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
if [ -z "$sm_token" ]; then
  sm_token="$(read_openhouse_service_manager_token || true)"
fi
if [ -z "$sm_token" ] && [ -n "$service_manager_bin" ]; then
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

start_service_if_present() {
  local service_id="$1"
  if curl -q -fsS --max-time 10 -X POST -K "$work_dir/curl.cfg" "$sm_url/api/v1/services/$service_id/start" >/dev/null 2>&1; then
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
else
  log "正在通过 service-manager 启动 group:local-stack。"
  curl -q -fsS --max-time 10 -X POST -K "$work_dir/curl.cfg" "$sm_url/api/v1/groups/local-stack/start" >/dev/null
fi

for _ in $(seq 1 45); do
  if stack_ready; then
    log "SmallPhoneAI 运行栈已就绪：pi-web=$pi_web_url service-manager=$sm_url SmallPhone=$smallphone_url SmallPhone core=$smallphone_core_url"
    log "cc-connect/openhouse-connect 为可修复的可选服务：$cc_url"
    exit 0
  fi
  sleep 1
done

warn "SmallPhoneAI 运行栈启动后仍未达到 pi 主线就绪条件。"
printf 'service-manager=%s\npi-web=%s\nSmallPhone=%s\nSmallPhone Core=%s\ncc-connect=%s\n' \
  "$(service_manager_ready && printf reachable || printf down)" \
  "$(probe_url "$pi_web_url" && printf reachable || printf down)" \
  "$(probe_url "$smallphone_url" && printf reachable || printf down)" \
  "$(probe_url "$smallphone_core_url" && printf reachable || printf down)" \
  "$(cc_connect_disabled && printf disabled || { probe_url "$cc_url" && printf reachable || printf down; })"
exit 1
SMALLPHONEAI_START
