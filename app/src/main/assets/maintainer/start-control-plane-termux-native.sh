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

canonical_port_open() {
  local bind="$1" host port
  host="${bind%:*}"
  port="${bind##*:}"
  [ -n "$host" ] && [ -n "$port" ] && [ "$host" != "$port" ] || return 1
  bash -c 'exec 3<>"/dev/tcp/$1/$2"' bash "$host" "$port" >/dev/null 2>&1
}

wait_for_canonical_service_manager() {
  local config="$1" url="$2" attempts="${3:-30}" attempt=1
  while [ "$attempt" -le "$attempts" ]; do
    if service_manager_health_ready "$url" \
      && service_manager_auth_ready "$config" "$url"; then
      return 0
    fi
    sleep 1
    attempt=$((attempt + 1))
  done
  return 1
}

start_control_plane() {
  local config bind url binary existing_pids log_dir log_file bootstrap_log started_pid

  is_termux_native || {
    warn "运行中枢只允许从 Termux native 环境启动。"
    return 2
  }
  command -v curl >/dev/null 2>&1 || {
    warn "缺少 curl，无法验证 service-manager health 与 canonical token。"
    return 2
  }

  config="$(find_canonical_config || true)"
  if [ -z "$config" ]; then
    warn "未找到现有 OpenHouse canonical service-manager 配置；不会创建或恢复配置。"
    return 2
  fi
  if [ -z "$(read_config_value "$config" auth_token authToken || true)" ]; then
    warn "canonical service-manager 配置缺少 token；不会修改配置：$config"
    return 2
  fi
  bind="$(config_bind "$config")" || {
    warn "canonical service-manager bind 无效：$config"
    return 2
  }
  url="$(config_url "$config")" || return 2

  if service_manager_health_ready "$url"; then
    if service_manager_auth_ready "$config" "$url"; then
      log "运行中枢已启动并通过 canonical config 认证：$url"
      return 0
    fi
    warn "service-manager API 可达，但 canonical config 认证不匹配；不会重启或覆盖现有实例。"
    return 1
  fi

  existing_pids="$(service_manager_serve_pids | tr '\n' ' ' | sed 's/[[:space:]]*$//' || true)"
  if [ -n "$existing_pids" ]; then
    warn "检测到已有 service-manager serve 进程但 canonical API 不可用；不会停止或替换：pids=$existing_pids"
    return 1
  fi
  if canonical_port_open "$bind"; then
    warn "canonical 端口已被其他进程占用；不会启动第二实例：$bind"
    return 1
  fi

  binary="$(find_installed_service_manager || true)"
  if [ -z "$binary" ]; then
    warn "未找到当前已安装的 Termux native service-manager；不会从 APK 安装或覆盖。"
    return 2
  fi

  log_dir="${SMALLPHONEAI_LOG_DIR:-$HOME/.smallphoneai/logs}"
  umask 077
  mkdir -p "$log_dir"
  chmod 700 "$log_dir" >/dev/null 2>&1 || true
  log_file="$log_dir/service-manager.log"
  bootstrap_log="$log_dir/service-manager-bootstrap.log"
  : > "$bootstrap_log"
  chmod 600 "$bootstrap_log" >/dev/null 2>&1 || true

  log "正在使用当前已安装 binary 和 canonical config 启动运行中枢：binary=$binary bind=$bind"
  nohup env \
    -u SERVICE_MANAGER_TOKEN \
    -u SMALLPHONE_SERVICE_MANAGER_TOKEN \
    "$binary" serve --config "$config" --bind "$bind" --log-file "$log_file" \
    </dev/null > "$bootstrap_log" 2>&1 &
  started_pid=$!

  if wait_for_canonical_service_manager "$config" "$url" \
    "${SMALLPHONEAI_SERVICE_MANAGER_READY_ATTEMPTS:-30}"; then
    log "运行中枢已启动并通过 canonical config 认证：$url"
    return 0
  fi

  kill "$started_pid" >/dev/null 2>&1 || true
  wait "$started_pid" >/dev/null 2>&1 || true
  warn "本次启动的 service-manager 未能通过有限等待与 canonical 认证，已停止该新进程；正式日志：$log_file；启动日志：$bootstrap_log"
  return 1
}

start_control_plane
