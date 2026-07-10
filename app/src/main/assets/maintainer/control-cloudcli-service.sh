#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-}"
PORT="${2:-${OPENHOUSE_CLOUDCLI_PORT:-${CLAUDE_CODE_UI_PORT:-23083}}}"
SERVICE_ID="cloudcli"
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"

log() {
  printf '[OpenHouseAI][cloudcli] %s\n' "$*"
}

warn() {
  printf '[OpenHouseAI][cloudcli] WARN: %s\n' "$*" >&2
}

die() {
  printf '[OpenHouseAI][cloudcli] ERROR: %s\n' "$*" >&2
  exit 1
}

setup_writable_tmpdir() {
  local dir
  for dir in \
    "${TMPDIR:-}" \
    "$TERMUX_PREFIX/tmp" \
    "$TERMUX_HOME/.tmp" \
    "$TERMUX_HOME/.cache/tmp"; do
    [ -n "$dir" ] || continue
    mkdir -p "$dir" 2>/dev/null || continue
    if [ -d "$dir" ] && [ -w "$dir" ]; then
      TMPDIR="$dir"
      export TMPDIR
      return 0
    fi
  done
  die "no writable temp directory is available for CloudCLI control."
}

case "$ACTION" in
  start|stop|restart|status) ;;
  *) die "usage: $0 {start|stop|restart|status} [port]" ;;
esac

case "$PORT" in
  ''|*[!0-9]*)
    die "invalid CloudCLI port: $PORT"
    ;;
esac

setup_writable_tmpdir

is_current_ubuntu() {
  [ -r /etc/os-release ] && grep -qi 'ubuntu' /etc/os-release
}

find_maintainer_script() {
  local name="$1"
  local dir
  for dir in \
    "${OPENHOUSEAI_MAINTAINER_DIR:-}" \
    "${SMALLPHONEAI_MAINTAINER_DIR:-}" \
    "$HOME/.smallphoneai-bootstrap/apk-assets/maintainer" \
    "$HOME/.smallphoneai-bootstrap/maintainer"; do
    [ -n "$dir" ] || continue
    if [ -f "$dir/$name" ]; then
      printf '%s\n' "$dir/$name"
      return 0
    fi
  done
  return 1
}

run_in_ubuntu() {
  if is_current_ubuntu; then
    "$@"
  else
    command -v proot-distro >/dev/null 2>&1 || return 127
    proot-distro login ubuntu -- "$@"
  fi
}

legacy_stop_cloudcli_port() {
  run_in_ubuntu sh -s "$PORT" <<'UBUNTU' || return 0
port="$1"
pids=""
self="$$"
proc_list="$(mktemp)"
trap 'rm -f "$proc_list"' EXIT
ps -eo pid=,comm=,args= > "$proc_list" 2>/dev/null || exit 0
while read -r pid comm args; do
  [ -n "$pid" ] || continue
  [ "$pid" = "$self" ] && continue
  case "$comm" in
    node|cloudcli) ;;
    *) continue ;;
  esac
  case " $args " in
    *cloudcli*|*dist-server/server/index.js*) ;;
    *) continue ;;
  esac
  case " $args " in
    *"--port $port"*|*"--port=$port"*|*"SERVER_PORT=$port"*|*"SERVER_PORT $port"*)
      pids="$pids $pid"
      ;;
  esac
done < "$proc_list"
if [ -n "$pids" ]; then
  kill $pids 2>/dev/null || true
  sleep 1
  kill -9 $pids 2>/dev/null || true
  printf 'stopped legacy CloudCLI processes:%s\n' "$pids"
fi
UBUNTU
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
  bind="$(normalize_service_manager_bind "${endpoint:-127.0.0.1:20087}")" || bind="127.0.0.1:20087"
  printf '%s://%s\n' "$scheme" "$bind"
}

read_config_token() {
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

find_service_manager_binary() {
  local candidate
  if command -v service-manager >/dev/null 2>&1; then
    command -v service-manager
    return 0
  fi
  for candidate in \
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/service-manager" \
    "$HOME/.local/bin/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/target/release/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/service-manager"; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

resolve_service_manager_token() {
  local token sm_bin
  token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  if [ -n "$token" ]; then
    printf '%s\n' "$token"
    return 0
  fi
  token="$(read_config_token || true)"
  if [ -n "$token" ]; then
    printf '%s\n' "$token"
    return 0
  fi
  sm_bin="$(find_service_manager_binary || true)"
  if [ -n "$sm_bin" ]; then
    "$sm_bin" token show 2>/dev/null | head -n 1 | tr -d '\r\n'
    return 0
  fi
  return 1
}

curl_with_auth() {
  local method="$1"
  local path="$2"
  local curl_cfg
  curl_cfg="$(mktemp "${TMPDIR:-/tmp}/cloudcli-control-curl.XXXXXX")"
  printf 'header = "Authorization: Bearer %s"\n' "$SERVICE_MANAGER_TOKEN_RESOLVED" > "$curl_cfg"
  curl -q -fsS --max-time 20 -X "$method" -K "$curl_cfg" "$SERVICE_MANAGER_URL_RESOLVED$path"
  local status=$?
  rm -f "$curl_cfg"
  return "$status"
}

register_cloudcli_if_needed() {
  local register_script
  register_script="$(find_maintainer_script register-cloudcli-service.sh || true)"
  [ -n "$register_script" ] || die "register-cloudcli-service.sh not found in maintainer assets."
  bash "$register_script" "$PORT"
}

wait_for_cloudcli_health() {
  local attempt
  for attempt in $(seq 1 60); do
    if curl -fsS --connect-timeout 2 --max-time 3 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
      return 0
    fi
    log "正在等待 CloudCLI 监听端口 $PORT"
    sleep 2
  done
  return 1
}

command -v curl >/dev/null 2>&1 || die "curl is required to control CloudCLI service."
SERVICE_MANAGER_URL_RESOLVED="$(configured_service_manager_url)"
SERVICE_MANAGER_TOKEN_RESOLVED="$(resolve_service_manager_token || true)"
[ -n "$SERVICE_MANAGER_TOKEN_RESOLVED" ] || die "service-manager token is unavailable."

if ! curl -fsS --max-time 3 "$SERVICE_MANAGER_URL_RESOLVED/api/v1/health" >/dev/null; then
  die "service-manager is not reachable at $SERVICE_MANAGER_URL_RESOLVED."
fi

case "$ACTION" in
  start)
    log "正在注册并通过 service-manager 启动 CloudCLI。"
    register_cloudcli_if_needed
    if ! curl_with_auth POST "/api/v1/services/$SERVICE_ID/start" >/dev/null; then
      warn "service-manager start failed; clearing legacy CloudCLI process on port $PORT and retrying."
      legacy_stop_cloudcli_port || true
      curl_with_auth POST "/api/v1/services/$SERVICE_ID/start" >/dev/null
    fi
    if ! wait_for_cloudcli_health; then
      warn "CloudCLI did not become healthy after start; clearing port and retrying once."
      curl_with_auth POST "/api/v1/services/$SERVICE_ID/stop" >/dev/null 2>&1 || true
      legacy_stop_cloudcli_port || true
      curl_with_auth POST "/api/v1/services/$SERVICE_ID/start" >/dev/null
      wait_for_cloudcli_health || die "CloudCLI did not become healthy on port $PORT."
    fi
    log "CloudCLI 已可通过端口 $PORT 访问。"
    ;;
  restart)
    log "正在注册并通过 service-manager 串行重启 CloudCLI。"
    register_cloudcli_if_needed
    curl_with_auth POST "/api/v1/services/$SERVICE_ID/stop" >/dev/null 2>&1 || true
    legacy_stop_cloudcli_port || true
    if ! curl_with_auth POST "/api/v1/services/$SERVICE_ID/start" >/dev/null; then
      warn "service-manager start after restart stop failed; clearing legacy CloudCLI process on port $PORT and retrying."
      legacy_stop_cloudcli_port || true
      curl_with_auth POST "/api/v1/services/$SERVICE_ID/start" >/dev/null
    fi
    if ! wait_for_cloudcli_health; then
      warn "CloudCLI did not become healthy after serial restart; retrying once."
      curl_with_auth POST "/api/v1/services/$SERVICE_ID/stop" >/dev/null 2>&1 || true
      legacy_stop_cloudcli_port || true
      curl_with_auth POST "/api/v1/services/$SERVICE_ID/start" >/dev/null
      wait_for_cloudcli_health || die "CloudCLI did not become healthy on port $PORT."
    fi
    log "CloudCLI 已在端口 $PORT 重启完成。"
    ;;
  stop)
    log "正在通过 service-manager 停止 CloudCLI。"
    if curl_with_auth POST "/api/v1/services/$SERVICE_ID/stop" >/dev/null; then
      legacy_stop_cloudcli_port || true
    else
      warn "service-manager stop failed; clearing legacy CloudCLI process on port $PORT."
      legacy_stop_cloudcli_port || true
    fi
    if curl -fsS --connect-timeout 2 --max-time 3 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
      die "CloudCLI is still reachable on port $PORT."
    fi
    log "CloudCLI $PORT 端口服务已停止。"
    ;;
  status)
    curl_with_auth GET "/api/v1/services/$SERVICE_ID/status"
    ;;
esac
