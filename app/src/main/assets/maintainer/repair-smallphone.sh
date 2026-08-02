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

if ! declare -F warn >/dev/null 2>&1; then
  warn() {
    printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
  }
fi

bootstrap="$(find_smallphoneai_bootstrap || true)"
if [ -n "$bootstrap" ]; then
  log "正在执行 SmallPhoneAI runtime hook：$bootstrap repair"
  run_logged env \
    OPENHOUSE_PI_RUNTIME="${OPENHOUSE_PI_RUNTIME:-termux}" \
    SMALLPHONEAI_PI_RUNTIME="${SMALLPHONEAI_PI_RUNTIME:-termux}" \
    OPENHOUSE_PI_NODE_RUNTIME="${OPENHOUSE_PI_NODE_RUNTIME:-termux}" \
    bash "$bootstrap" repair
  exit $?
fi

log "未找到 SmallPhoneAI bootstrap.sh，使用 APK 内置修复钩子检查、注册并启动已安装组件。"
require_ubuntu

set +e
run_ubuntu_logged bash <<'SMALLPHONEAI_REPAIR'
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
}

openhouse_tmp_parent() {
  local dir="${TMPDIR:-}"
  while [ -n "$dir" ] && [ "$dir" != "/" ] && [ "${dir%/}" != "$dir" ]; do
    dir="${dir%/}"
  done
  if [ -z "$dir" ] || [ "$dir" = "/""tmp" ]; then
    if [ -n "${PREFIX:-}" ]; then
      dir="$PREFIX/tmp"
    else
      dir="${HOME:-.}/.tmp"
    fi
  fi
  mkdir -p "$dir" || {
    warn "无法创建临时目录：$dir"
    return 1
  }
  printf '%s\n' "$dir"
}

openhouse_mktemp_dir() {
  local template="$1"
  local parent
  parent="$(openhouse_tmp_parent)" || return 1
  mktemp -d "$parent/$template"
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
bind="$(configured_service_manager_bind)"
sm_url="$(configured_service_manager_url)"
smallphone_url="${SMALLPHONEAI_SMALLPHONE_URL:-http://127.0.0.1:22082/}"
smallphone_core_url="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-http://127.0.0.1:22000/}"
pi_runtime_host="${OPENHOUSE_PI_RUNTIME_HOST:-127.0.0.1}"
pi_runtime_port="${OPENHOUSE_PI_RUNTIME_PORT:-20765}"
cc_url="${SMALLPHONEAI_CC_CONNECT_URL:-http://127.0.0.1:21040/}"
log_dir="${SMALLPHONEAI_LOG_DIR:-$HOME/.smallphoneai/logs}"
failures=0

export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:$PATH"

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

run_component_script() {
  local label="$1"
  local dir="$2"
  local script="$3"
  local required="$4"
  local path="$dir/$script"

  if [ ! -f "$path" ]; then
    if [ "$required" = "1" ]; then
      warn "$label: 缺少必要入口 $path"
      failures=$((failures + 1))
    else
      warn "$label: 可选入口不存在，跳过 $path"
    fi
    return 0
  fi

  chmod +x "$path"
  log "$label: 执行 $script"
  if ! (cd "$dir" && "./$script"); then
    if [ "$required" = "1" ]; then
      warn "$label: $script 执行失败"
      failures=$((failures + 1))
    else
      warn "$label: 可选入口 $script 执行失败，继续。"
    fi
  fi
}

run_component() {
  local label="$1"
  local dir="$2"
  local required="$3"

  if [ ! -d "$dir" ]; then
    if [ "$required" = "1" ]; then
      warn "$label: 仓库不存在：$dir"
      failures=$((failures + 1))
    else
      warn "$label: 仓库不存在，跳过：$dir"
    fi
    return 0
  fi

  run_component_script "$label" "$dir" "scripts/install.sh" "$required"
  run_component_script "$label" "$dir" "scripts/check.sh" "$required"
  run_component_script "$label" "$dir" "scripts/register-service.sh" "0"
}

probe_url() {
  command -v curl >/dev/null 2>&1 || return 1
  curl -fsS --max-time 2 "$1" >/dev/null 2>&1
}

probe_tcp() {
  local host="$1" port="$2"
  command -v timeout >/dev/null 2>&1 || return 1
  timeout 2 bash -c ': >/dev/tcp/$1/$2' _ "$host" "$port" >/dev/null 2>&1
}

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

service_manager_ready() {
  probe_url "$sm_url/api/v1/health" || probe_url "$sm_url/"
}

stack_ready() {
  service_manager_ready \
    && probe_tcp "$pi_runtime_host" "$pi_runtime_port"
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

log "检查并注册 SmallPhoneAI 运行组件。"
run_component "service-manager" "$service_manager_dir" "1"
run_component "cc-connect/openhouse-connect" "$cc_connect_dir" "0"
run_component "SmallPhone compatibility service" "$smallphone_dir" "0"
run_component "pi-agent" "$pi_agent_dir" "1"

if [ "$failures" -ne 0 ]; then
  warn "组件修复存在 $failures 个失败项，继续尝试启动已可用的注册项。"
fi

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

work_dir="$(openhouse_mktemp_dir "smallphoneai-repair.XXXXXX")"
cleanup() {
  rm -rf "$work_dir" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT HUP TERM
printf 'header = "Authorization: Bearer %s"\n' "$sm_token" > "$work_dir/curl.cfg"

log "正在通过 service-manager 启动 WuxianPi 核心服务。"
curl -q -fsS --max-time 10 -X POST -K "$work_dir/curl.cfg" \
  "$sm_url/api/v1/services/yuanshengwuxianpi/start" >/dev/null

for _ in $(seq 1 45); do
  if stack_ready; then
    log "SmallPhoneAI 运行栈已就绪：pi-agent=${pi_runtime_host}:${pi_runtime_port} service-manager=$sm_url"
    log "cc-connect/openhouse-connect 为可修复的可选服务：$cc_url"
    exit 0
  fi
  sleep 1
done

warn "SmallPhoneAI 修复后仍未达到 pi 主线就绪条件。"
printf 'service-manager=%s\npi-agent=%s\nSmallPhone=%s\nSmallPhone Core=%s\ncc-connect=%s\n' \
  "$(service_manager_ready && printf reachable || printf down)" \
  "$(probe_tcp "$pi_runtime_host" "$pi_runtime_port" && printf reachable || printf down)" \
  "$(probe_url "$smallphone_url" && printf reachable || printf down)" \
  "$(probe_url "$smallphone_core_url" && printf reachable || printf down)" \
  "$(cc_connect_disabled && printf disabled || { probe_url "$cc_url" && printf reachable || printf down; })"
exit 1
SMALLPHONEAI_REPAIR
smallphone_status=$?
set -e
exit "$smallphone_status"
