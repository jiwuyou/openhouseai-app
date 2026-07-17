#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
}

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

find_canonical_service_manager_config() {
  local candidate
  for candidate in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
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

service_manager_get() {
  local path="$1"
  curl -q -fsS --max-time 10 \
    -H "Authorization: Bearer $service_manager_token" \
    "$service_manager_url$path"
}

service_manager_post() {
  local path="$1"
  curl -q -fsS --max-time 20 \
    -X POST \
    -H "Authorization: Bearer $service_manager_token" \
    "$service_manager_url$path" >/dev/null
}

json_field() {
  local expression="$1"
  if command -v jq >/dev/null 2>&1; then
    jq -r "$expression" 2>/dev/null || true
  else
    printf ''
  fi
}

report_aionui_health() {
  local status_body endpoint_body state endpoint_status endpoint_url reachable
  status_body="$(service_manager_get /api/v1/services/aionui-web/status || true)"
  state="$(printf '%s' "$status_body" | json_field '.state // .status')"
  endpoint_body="$(service_manager_get /api/v1/services/aionui-web/endpoints/web || true)"
  endpoint_status="$(printf '%s' "$endpoint_body" | json_field '.status')"
  endpoint_url="$(printf '%s' "$endpoint_body" | json_field '.url')"
  reachable=no
  if [ -n "$endpoint_url" ] && curl -q -fsS --max-time 5 "$endpoint_url" >/dev/null 2>&1; then
    reachable=yes
  fi
  if [ "$state" = "running" ] && [ "$endpoint_status" = "healthy" ] && [ "$reachable" = yes ]; then
    log "最终状态记录：AionUi 已健康就绪：$endpoint_url"
  else
    warn "最终状态记录：AionUi 尚未健康（不阻塞 SmallPhone）：state=${state:-unknown}, endpoint=${endpoint_status:-unknown}, reachable=$reachable"
  fi
}

wait_for_endpoint() {
  local service_id="$1"
  local endpoint_name="$2"
  local label="$3"
  local attempt body status url
  for attempt in $(seq 1 60); do
    body="$(service_manager_get "/api/v1/services/$service_id/endpoints/$endpoint_name" || true)"
    status="$(printf '%s' "$body" | json_field '.status')"
    url="$(printf '%s' "$body" | json_field '.url')"
    if [ "$status" = "healthy" ] && [ -n "$url" ]; then
      log "$label endpoint 已健康：$url"
      printf '%s\n' "$url"
      return 0
    fi
    if [ "$attempt" -eq 1 ] || [ $((attempt % 10)) -eq 0 ]; then
      log "等待 $label endpoint：status=${status:-unknown}"
    fi
    sleep 2
  done
  log "$label endpoint 在超时时间内未 healthy。"
  return 1
}

require_ubuntu

bootstrap="$(find_smallphoneai_bootstrap || true)"
[ -n "$bootstrap" ] || { log "未找到 APK 内置 SmallPhoneAI bootstrap。"; exit 1; }
[ -n "${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-}" ] || {
  log "未提供版本化 SmallPhone payload 目录。"
  exit 1
}

config="$(find_canonical_service_manager_config || true)"
[ -n "$config" ] || { log "未找到 canonical service-manager 配置。"; exit 1; }
service_manager_endpoint="$(read_config_value "$config" listen_addr listenAddr bind bind_addr base_url baseUrl baseURL url || true)"
case "$service_manager_endpoint" in
  https://*) service_manager_scheme=https ;;
  *) service_manager_scheme=http ;;
esac
service_manager_bind="$(normalize_bind "${service_manager_endpoint:-127.0.0.1:20087}")"
service_manager_url="$service_manager_scheme://$service_manager_bind"
service_manager_token="$(read_config_value "$config" auth_token authToken || true)"
[ -n "$service_manager_token" ] || { log "canonical service-manager 配置缺少 auth_token。"; exit 1; }
command -v curl >/dev/null 2>&1 || { log "缺少 curl，无法调用 service-manager。"; exit 1; }
command -v jq >/dev/null 2>&1 || { log "缺少 jq，无法解析动态 endpoint；请先完成运行环境准备。"; exit 1; }

service_manager_get /api/v1/health >/dev/null || {
  log "service-manager 不可达：$service_manager_url"
  exit 1
}

payload_dir="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR}"
log "使用版本化 SmallPhone payload：$payload_dir"
run_logged env \
  SMALLPHONEAI_COMPONENT_TARGETS=smallphone \
  SMALLPHONEAI_COMPONENT_ACTION=install-register \
  SMALLPHONEAI_REQUIRE_SERVICE_MANAGER_READY=1 \
  SMALLPHONEAI_COMPONENT_SOURCE_MODE=bundle \
  SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE=0 \
  SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="$payload_dir" \
  SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="$payload_dir" \
  bash "$bootstrap" install-smallphone

log "SmallPhone 注册完成，启动动态 endpoint。"
service_manager_post /api/v1/services/smallphone-core/start || {
  log "SmallPhone core 启动请求失败。"
  exit 1
}
wait_for_endpoint smallphone-core api "SmallPhone core" >/dev/null

service_manager_post /api/v1/services/smallphone-frontend-beta/start || {
  log "SmallPhone Front Beta 启动请求失败。"
  exit 1
}
wait_for_endpoint smallphone-frontend-beta web "SmallPhone Front Beta" >/dev/null

report_aionui_health
log "SmallPhone 已安装、注册并通过动态 endpoint 健康检查。"
