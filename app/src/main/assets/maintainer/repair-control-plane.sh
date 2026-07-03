set -euo pipefail

log "正在轻量修复控制中枢：只恢复 service-manager 与 OpenHouse 专用 token。"
require_ubuntu

read_openhouse_service_manager_endpoint() {
  local config key value
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}"; do
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

start_ubuntu_service_manager_from_termux() {
  local bind
  local sm_url
  bind="$(configured_service_manager_bind)"
  sm_url="$(configured_service_manager_url)"
  local launcher_dir="$HOME/.smallphoneai/service-manager"
  local launcher="$launcher_dir/service-manager-proot-launcher.sh"

  command -v proot-distro >/dev/null 2>&1 || return 1
  mkdir -p "$launcher_dir"

  if command -v curl >/dev/null 2>&1 && curl -fsS --max-time 2 "$sm_url/api/v1/health" >/dev/null 2>&1; then
    log "service-manager 已在 Termux 侧可访问：$sm_url"
    export SMALLPHONEAI_TERMUX_SERVICE_MANAGER_LAUNCHED=1
    return 0
  fi

cat > "$launcher" <<'SMALLPHONEAI_SERVICE_MANAGER_LAUNCHER'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

read_openhouse_service_manager_endpoint() {
  local config key value
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}"; do
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

bind="$(configured_service_manager_bind)"
export SMALLPHONEAI_SERVICE_MANAGER_BIND="$bind"
exec proot-distro login ubuntu -- env \
  SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-}" \
  SMALLPHONEAI_SERVICE_MANAGER_BIND="$bind" \
  SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG="${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
  SMALLPHONEAI_TERMUX_HOME="${SMALLPHONEAI_TERMUX_HOME:-${HOME:-/data/data/com.termux/files/home}}" \
  bash -lc '
set -euo pipefail

bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-}"
[ -n "$bind" ] || bind="127.0.0.1:20087"
repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$HOME/smallphoneai-repos}"
log_dir="${SMALLPHONEAI_LOG_DIR:-$HOME/.smallphoneai/logs}"
service_manager_dir="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-$repo_root/service-manager}"
export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
mkdir -p "$log_dir"

find_service_manager() {
  if command -v service-manager >/dev/null 2>&1; then
    command -v service-manager
    return 0
  fi
  for candidate in \
    "$HOME/.local/bin/service-manager" \
    "$service_manager_dir/service-manager" \
    "$service_manager_dir/target/release/service-manager" \
    "$service_manager_dir/target/debug/service-manager" \
    "$repo_root/service-manager/service-manager" \
    "$repo_root/service-manager/target/release/service-manager" \
    "$repo_root/service-manager/target/debug/service-manager"; do
    if [ -x "$candidate" ]; then
      printf "%s\n" "$candidate"
      return 0
    fi
  done
  return 1
}

service_manager_bin="$(find_service_manager)"
exec "$service_manager_bin" serve --bind "$bind"
'
SMALLPHONEAI_SERVICE_MANAGER_LAUNCHER

  chmod 700 "$launcher" >/dev/null 2>&1 || true
  log "正在从 Termux 侧拉起长期运行的 service-manager proot 会话：$bind"
  if command -v setsid >/dev/null 2>&1; then
    (trap '' HUP; SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-}" SMALLPHONEAI_SERVICE_MANAGER_BIND="$bind" SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG="${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" SMALLPHONEAI_TERMUX_HOME="${SMALLPHONEAI_TERMUX_HOME:-${HOME:-/data/data/com.termux/files/home}}" setsid -f "$launcher" > "$launcher_dir/proot-launcher.log" 2>&1 < /dev/null) || true
  else
    (trap '' HUP; SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-}" SMALLPHONEAI_SERVICE_MANAGER_BIND="$bind" SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG="${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" SMALLPHONEAI_TERMUX_HOME="${SMALLPHONEAI_TERMUX_HOME:-${HOME:-/data/data/com.termux/files/home}}" nohup "$launcher" > "$launcher_dir/proot-launcher.log" 2>&1 < /dev/null &)
  fi

  for _ in $(seq 1 20); do
    if command -v curl >/dev/null 2>&1 && curl -fsS --max-time 2 "$sm_url/api/v1/health" >/dev/null 2>&1; then
      log "service-manager 已由 Termux 侧长期会话启动：$sm_url"
      export SMALLPHONEAI_TERMUX_SERVICE_MANAGER_LAUNCHED=1
      return 0
    fi
    sleep 1
  done

  log "Termux 侧 service-manager 长期会话启动后仍不可访问：$sm_url"
  if [ -f "$launcher_dir/proot-launcher.log" ]; then
    tail -n 80 "$launcher_dir/proot-launcher.log" | while IFS= read -r line; do log "$line"; done
  fi
  export SMALLPHONEAI_TERMUX_SERVICE_MANAGER_LAUNCHED=1
  return 1
}

if ! is_current_ubuntu; then
  start_ubuntu_service_manager_from_termux || true
fi

run_ubuntu_logged bash <<'SMALLPHONEAI_REPAIR_CONTROL_PLANE'
set -euo pipefail

log() {
  printf '[SmallPhoneAI control-plane] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI control-plane] WARN: %s\n' "$*" >&2
}

read_openhouse_service_manager_endpoint() {
  local config key value
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}"; do
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
bind="$(configured_service_manager_bind)"
sm_url="$(configured_service_manager_url)"
log_dir="${SMALLPHONEAI_LOG_DIR:-$HOME/.smallphoneai/logs}"

export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
export SERVICE_MANAGER_URL="$sm_url"

probe_url() {
  local url="$1"
  command -v curl >/dev/null 2>&1 || return 1
  curl -fsS --max-time 2 "$url" >/dev/null 2>&1
}

service_manager_ready() {
  probe_url "$sm_url/api/v1/health" || probe_url "$sm_url/"
}

find_service_manager() {
  if command -v service-manager >/dev/null 2>&1; then
    command -v service-manager
    return 0
  fi

  for candidate in \
    "$HOME/.local/bin/service-manager" \
    "$service_manager_dir/service-manager" \
    "$service_manager_dir/target/release/service-manager" \
    "$service_manager_dir/target/debug/service-manager" \
    "$repo_root/service-manager/service-manager" \
    "$repo_root/service-manager/target/release/service-manager" \
    "$repo_root/service-manager/target/debug/service-manager"; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

read_token_from_config() {
  local config_path
  local token

  for config_path in \
    "$HOME/.config/service-manager/config.json" \
    "/data/data/com.termux/files/home/.config/service-manager/config.json"; do
    [ -f "$config_path" ] || continue
    token="$(sed -n 's/.*"auth_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$config_path" | head -n 1 || true)"
    if [ -n "$token" ]; then
      printf '%s' "$token"
      return 0
    fi
  done

  return 1
}

resolve_service_manager_token() {
  local token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"

  if [ -z "$token" ]; then
    token="$("$service_manager_bin" token show 2>/dev/null | tr -d '\r\n' || true)"
  fi
  if [ -z "$token" ]; then
    token="$(read_token_from_config || true)"
  fi

  printf '%s' "$token"
}

service_manager_auth_ready() {
  local token="$1"
  local work_dir
  local curl_cfg
  local status

  [ -n "$token" ] || return 1
  command -v curl >/dev/null 2>&1 || return 1

  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-control-plane.XXXXXX")" || return 1
  curl_cfg="$work_dir/curl.cfg"
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$curl_cfg"
  curl -q -fsS --max-time 3 -K "$curl_cfg" "$sm_url/api/v1/services" >/dev/null 2>&1
  status=$?
  rm -rf "$work_dir" >/dev/null 2>&1 || true
  return "$status"
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
    /data/data/com.termux/files/home/*)
      if [ ! -d "/data/data/com.termux/files/home" ]; then
        warn "当前 Ubuntu/proot 环境未暴露 Termux home，跳过写入：$target"
        return 1
      fi
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
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json" \
    "$HOME/.config/openhouseai/service-manager/config.json"; do
    [ -n "$target" ] || continue
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

stop_service_manager_processes() {
  local self="$$"
  ps -eo pid=,comm=,args= 2>/dev/null | while read -r pid comm args; do
    [ -n "$pid" ] || continue
    [ "$pid" = "$self" ] && continue
    case "$comm $args" in
      *service-manager*" serve "*|*service-manager*" serve")
        kill "$pid" >/dev/null 2>&1 || true
        ;;
    esac
  done
  sleep 1
}

start_service_manager() {
  mkdir -p "$log_dir"
  if [ "${SMALLPHONEAI_TERMUX_SERVICE_MANAGER_LAUNCHED:-}" = "1" ]; then
    warn "Termux 侧长期 service-manager 会话未通过 health 检查；拒绝在 proot 子会话内短暂启动，避免脚本假成功。"
    return 1
  fi

  log "正在启动 service-manager：$bind"

  if command -v setsid >/dev/null 2>&1; then
    (trap '' HUP; setsid -f "$service_manager_bin" serve --bind "$bind" > "$log_dir/service-manager.log" 2>&1 < /dev/null) || true
  else
    (trap '' HUP; nohup "$service_manager_bin" serve --bind "$bind" > "$log_dir/service-manager.log" 2>&1 < /dev/null &)
  fi

  for _ in $(seq 1 30); do
    if service_manager_ready; then
      log "service-manager 已启动：$sm_url"
      return 0
    fi
    sleep 1
  done

  warn "service-manager 未能启动。日志：$log_dir/service-manager.log"
  if [ -f "$log_dir/service-manager.log" ]; then
    tail -n 80 "$log_dir/service-manager.log" >&2 || true
  fi
  return 1
}

sync_registry_with_api() {
  local token="$1"
  local work_dir
  local curl_cfg

  command -v curl >/dev/null 2>&1 || {
    warn "缺少 curl，跳过 registry sync。"
    return 0
  }
  [ -n "$token" ] || {
    warn "token 为空，跳过 registry sync。"
    return 0
  }

  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-registry-sync.XXXXXX")" || {
    warn "无法创建临时目录，跳过 registry sync。"
    return 0
  }
  curl_cfg="$work_dir/curl.cfg"
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$curl_cfg"

  if curl -q -fsS --max-time 10 -X POST -K "$curl_cfg" "$sm_url/api/v1/registry/sync" >/dev/null 2>&1; then
    log "OpenHouseAI registry 已通过 service-manager API 同步。"
  else
    warn "service-manager registry sync API 调用失败；为避免触发 Python/apt，跳过 fallback 同步。"
  fi

  rm -rf "$work_dir" >/dev/null 2>&1 || true
}

service_manager_bin="$(find_service_manager || true)"
if [ -z "$service_manager_bin" ]; then
  warn "找不到 service-manager 二进制，无法轻量修复控制中枢。"
  exit 2
fi

export PATH="$(dirname "$service_manager_bin"):$PATH"
mkdir -p "$log_dir"

if service_manager_ready; then
  log "service-manager 已可访问：$sm_url"
else
  start_service_manager || true
fi

if ! service_manager_ready; then
  warn "service-manager 最终 health 检查失败：$sm_url/api/v1/health"
  exit 1
fi

sm_token="$(resolve_service_manager_token)"
if ! service_manager_auth_ready "$sm_token"; then
  warn "service-manager token 与当前运行实例不匹配，重启本地 service-manager。"
  stop_service_manager_processes
  start_service_manager || true
  sm_token="$(resolve_service_manager_token)"
fi

if [ -z "$sm_token" ] || ! service_manager_auth_ready "$sm_token"; then
  warn "service-manager 已启动，但无法获得可用 token；控制页仍会无法授权。"
  exit 1
fi

export SERVICE_MANAGER_TOKEN="$sm_token"
export SMALLPHONE_SERVICE_MANAGER_TOKEN="${SMALLPHONE_SERVICE_MANAGER_TOKEN:-$sm_token}"
sync_openhouse_service_manager_config "$sm_token" "$(service_manager_listen_addr)" || true
sync_registry_with_api "$sm_token"

if service_manager_ready && service_manager_auth_ready "$sm_token"; then
  log "控制中枢轻量修复完成：service-manager=$sm_url"
  exit 0
fi

warn "控制中枢轻量修复后验证失败。"
exit 1
SMALLPHONEAI_REPAIR_CONTROL_PLANE
