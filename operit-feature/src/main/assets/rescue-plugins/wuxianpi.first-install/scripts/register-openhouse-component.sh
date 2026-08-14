#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SERVICE_ID="yuanshengwuxianpi"
COMPONENT_ID="$SERVICE_ID"
DEFAULT_SERVICE_MANAGER_URL="http://127.0.0.1:20087"
CANONICAL_CONFIG="$HOME/.config/openhouseai/service-manager/config.json"
SERVICE_SPEC="$HOME/.config/openhouseai/service-manager/services.d/$SERVICE_ID.json"
WORK_DIR=""

log() { printf '[OpenHouse registry] %s\n' "$*"; }
die() { printf '[OpenHouse registry] ERROR: %s\n' "$*" >&2; exit 1; }

cleanup() {
  [[ -z "$WORK_DIR" || ! -d "$WORK_DIR" ]] || rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT

service_manager_url() {
  local value="${SERVICE_MANAGER_URL:-${SMALLPHONEAI_SERVICE_MANAGER_URL:-}}"
  case "$value" in
    http://0.0.0.0*) printf 'http://127.0.0.1%s\n' "${value#http://0.0.0.0}" ;;
    https://0.0.0.0*) printf 'https://127.0.0.1%s\n' "${value#https://0.0.0.0}" ;;
    http://*|https://*) printf '%s\n' "${value%/}" ;;
    *) printf '%s\n' "$DEFAULT_SERVICE_MANAGER_URL" ;;
  esac
}

service_manager_token() {
  local config="${SERVICE_MANAGER_CONFIG_PATH:-$CANONICAL_CONFIG}" token
  token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  if [[ -z "$token" && -x "$(command -v service-manager || true)" && -f "$config" ]]; then
    token="$(service-manager token show --config "$config" 2>/dev/null | head -n 1 | tr -d '\r\n' || true)"
  fi
  [[ -n "$token" ]] || return 1
  printf '%s\n' "$token"
}

api_request() {
  local method="$1" path="$2" auth_file="$3" body_file="${4:-}"
  if [[ -n "$body_file" ]]; then
    curl -q -fsS --max-time 15 -K "$auth_file" -H 'Content-Type: application/json' \
      -X "$method" --data-binary "@$body_file" "$(service_manager_url)$path"
  else
    curl -q -fsS --max-time 15 -K "$auth_file" -X "$method" "$(service_manager_url)$path"
  fi
}

write_component_manifest() {
  cat >"$1" <<'JSON'
{
  "schemaVersion": 1,
  "id": "yuanshengwuxianpi",
  "title": "WuxianPi AI",
  "description": "WuxianPi 本地救援 AI",
  "kind": "app",
  "enabled": true,
  "shellMenu": {
    "visible": true,
    "section": "ai",
    "order": 80,
    "title": "WuxianPi AI",
    "subtitle": "本地救援 AI",
    "entry": {"type": "webview", "url": "http://127.0.0.1:20765/"},
    "controlEntry": {
      "type": "service-control",
      "serviceNames": ["yuanshengwuxianpi"],
      "serviceRefs": ["service-manager://services/yuanshengwuxianpi"]
    },
    "desktop": {"visible": true, "pinned": true, "order": 80, "icon": "brain"}
  },
  "smallphoneApp": {
    "visible": true,
    "section": "ai",
    "order": 80,
    "icon": "brain",
    "entry": {"type": "webview", "url": "http://127.0.0.1:20765/"},
    "controlEntry": {
      "type": "service-control",
      "serviceNames": ["yuanshengwuxianpi"],
      "serviceRefs": ["service-manager://services/yuanshengwuxianpi"]
    }
  },
  "serviceManager": {
    "required": true,
    "services": [{
      "name": "yuanshengwuxianpi",
      "title": "WuxianPi AI",
      "role": "web",
      "port": 20765,
      "url": "http://127.0.0.1:20765/",
      "serviceRef": "service-manager://services/yuanshengwuxianpi",
      "health": {"type": "http", "url": "http://127.0.0.1:20765/health"},
      "controls": ["status", "start", "stop", "restart", "logs"]
    }]
  },
  "ai": {
    "visible": true,
    "summary": "WuxianPi 本地救援 AI Web UI，运行于 yuanshengwuxianpi。",
    "intents": [
      {"name": "open", "target": "smallphoneApp.entry"},
      {"name": "control", "target": "smallphoneApp.controlEntry"}
    ]
  }
}
JSON
}

prepare_auth() {
  local token
  token="$(service_manager_token)" || die "无法读取 canonical service-manager token：$CANONICAL_CONFIG"
  printf 'header = "Authorization: Bearer %s"\n' "$token" >"$1"
  chmod 600 "$1"
}

register_component() {
  local manifest payload auth_file
  command -v jq >/dev/null 2>&1 || die '缺少 jq'
  command -v curl >/dev/null 2>&1 || die '缺少 curl'
  [[ -s "$SERVICE_SPEC" ]] || die "Runtime 未生成服务定义：$SERVICE_SPEC"
  jq -e --arg id "$SERVICE_ID" '
    .name == $id and .provider == "termux-process" and
    any(.ports[]?; .name == "runtime" and .preferred == 20765 and .dynamic == true and .envVar == "OPENHOUSE_PI_PORT") and
    any(.health[]?; .url == "http://127.0.0.1:{{port:runtime}}/health")
  ' "$SERVICE_SPEC" >/dev/null || die "Runtime 服务定义缺少动态 runtime 端口或模板化健康检查"

  WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/wuxianpi-openhouse-registry.XXXXXX")"
  manifest="$WORK_DIR/component.json"
  payload="$WORK_DIR/apply.json"
  auth_file="$WORK_DIR/curl.cfg"
  write_component_manifest "$manifest"
  prepare_auth "$auth_file"
  jq -n --slurpfile component "$manifest" --slurpfile service "$SERVICE_SPEC" --arg id "$SERVICE_ID" \
    '{component:$component[0],services:[{id:$id,service:$service[0]}]}' >"$payload"

  api_request POST /api/v1/registry/apply "$auth_file" "$payload" >/dev/null \
    || die 'registry/apply 未能同时注册 WuxianPi 服务和组件'
  api_request POST /api/v1/registry/sync "$auth_file" >/dev/null \
    || die 'registry/sync 失败'
  log "已注册服务和组件：$COMPONENT_ID"
}

verify_component() {
  local attempt auth_file services components endpoint endpoint_url endpoint_port
  command -v jq >/dev/null 2>&1 || die '缺少 jq'
  WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/wuxianpi-openhouse-verify.XXXXXX")"
  auth_file="$WORK_DIR/curl.cfg"
  prepare_auth "$auth_file"
  services="$(api_request GET /api/v1/services "$auth_file")" || die '无法读取 service-manager 服务列表'
  jq -e --arg id "$SERVICE_ID" 'any(.[]; .id == $id or .name == $id)' <<<"$services" >/dev/null \
    || die "服务列表中没有 $SERVICE_ID"
  components="$(api_request GET /api/v1/registry/components "$auth_file")" || die '无法读取组件列表'
  jq -e --arg id "$COMPONENT_ID" 'any(.[]; .id == $id)' <<<"$components" >/dev/null \
    || die "组件列表中没有 $COMPONENT_ID"
  endpoint=''
  for attempt in $(seq 1 10); do
    endpoint="$(api_request GET "/api/v1/services/$SERVICE_ID/endpoints/runtime" "$auth_file" 2>/dev/null || true)"
    jq -e '.name == "runtime" and (.port | type == "number") and (.url | type == "string")' <<<"$endpoint" >/dev/null 2>&1 && break
    endpoint=''
    sleep 1
  done
  [[ -n "$endpoint" ]] || die 'WuxianPi runtime 端点未在 10 秒内就绪'
  endpoint_url="$(jq -r '.url' <<<"$endpoint")"
  endpoint_port="$(jq -r '.port' <<<"$endpoint")"
  curl -q -fsS --max-time 5 "${endpoint_url%/}/health" >/dev/null \
    || die "WuxianPi $endpoint_port health 检查失败"
  printf 'registry_component=%s\nregistry_api=ok\nservice=ok\nendpoint_runtime=%s\nhealth=ok\n' "$COMPONENT_ID" "$endpoint_port"
}

case "${1:-}" in
  register) register_component ;;
  verify|status) verify_component ;;
  *) die "用法：$0 register|verify" ;;
esac
