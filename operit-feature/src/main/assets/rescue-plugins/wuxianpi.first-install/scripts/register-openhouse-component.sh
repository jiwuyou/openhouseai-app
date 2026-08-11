#!/usr/bin/env bash
set -euo pipefail

SERVICE_ID="yuanshengwuxianpi"
COMPONENT_ID="$SERVICE_ID"
LEGACY_COMPONENT_ID="pi-agent"
COMPONENT_URL="http://127.0.0.1:20765/"
DEFAULT_SERVICE_MANAGER_URL="http://127.0.0.1:20087"
REGISTRY_WORK_DIR=""

log() {
  printf '[OpenHouse registry] %s\n' "$*"
}

warn() {
  printf '[OpenHouse registry] WARN: %s\n' "$*" >&2
}

die() {
  warn "$*"
  exit 1
}

cleanup_registry_work_dir() {
  if [ -n "$REGISTRY_WORK_DIR" ]; then
    rm -rf -- "$REGISTRY_WORK_DIR"
    REGISTRY_WORK_DIR=""
  fi
}

service_manager_config() {
  printf '%s\n' "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-${SERVICE_MANAGER_CONFIG_PATH:-$HOME/.config/openhouseai/service-manager/config.json}}"
}

service_manager_url() {
  local config value
  if [ -n "${SERVICE_MANAGER_URL:-}" ]; then
    value="$SERVICE_MANAGER_URL"
  elif [ -n "${SMALLPHONEAI_SERVICE_MANAGER_URL:-}" ]; then
    value="$SMALLPHONEAI_SERVICE_MANAGER_URL"
  else
    value="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-}"
  fi
  config="$(service_manager_config)"
  if [ -z "$value" ] && [ -f "$config" ]; then
    value="$(sed -n 's/.*"\(listen_addr\|listenAddr\|base_url\|baseUrl\|baseURL\|url\)"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\2/p' "$config" | head -n 1 || true)"
  fi
  case "$value" in
    http://0.0.0.0*) printf 'http://127.0.0.1%s\n' "${value#http://0.0.0.0}" ;;
    https://0.0.0.0*) printf 'https://127.0.0.1%s\n' "${value#https://0.0.0.0}" ;;
    http://*|https://*) printf '%s\n' "${value%/}" ;;
    :*) printf 'http://127.0.0.1%s\n' "$value" ;;
    0.0.0.0:*) printf 'http://127.0.0.1:%s\n' "${value#0.0.0.0:}" ;;
    *:*) printf 'http://%s\n' "$value" ;;
    *) printf '%s\n' "$DEFAULT_SERVICE_MANAGER_URL" ;;
  esac
}

service_manager_token() {
  local config token binary
  if [ -n "${SERVICE_MANAGER_TOKEN:-}" ]; then
    printf '%s\n' "$SERVICE_MANAGER_TOKEN"
    return 0
  fi
  if [ -n "${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}" ]; then
    printf '%s\n' "$SMALLPHONE_SERVICE_MANAGER_TOKEN"
    return 0
  fi
  config="$(service_manager_config)"
  binary="$(command -v service-manager || true)"
  if [ -n "$binary" ] && [ -f "$config" ]; then
    token="$($binary token show --config "$config" 2>/dev/null | head -n 1 | tr -d '\r\n' || true)"
    [ -n "$token" ] && { printf '%s\n' "$token"; return 0; }
  fi
  if [ -f "$config" ]; then
    token="$(sed -n 's/.*"\(auth_token\|authToken\)"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\2/p' "$config" | head -n 1 || true)"
    [ -n "$token" ] && { printf '%s\n' "$token"; return 0; }
  fi
  return 1
}

write_component_manifest() {
  local target="$1"
  cat > "$target" <<'JSON'
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
    "entry": {
      "type": "webview",
      "url": "http://127.0.0.1:20765/"
    },
    "desktop": {
      "visible": true,
      "pinned": true,
      "order": 80,
      "icon": "brain"
    }
  },
  "smallphoneApp": {
    "visible": true,
    "section": "ai",
    "order": 80,
    "icon": "brain",
    "entry": {
      "type": "webview",
      "url": "http://127.0.0.1:20765/"
    }
  },
  "serviceManager": {},
  "ai": {
    "visible": true,
    "summary": "WuxianPi 本地救援 AI Web UI，运行于 yuanshengwuxianpi。"
  }
}
JSON
}

legacy_component_file() {
  printf '%s\n' "$HOME/.config/openhouseai/components.d/$LEGACY_COMPONENT_ID.json"
}

is_known_legacy_component() {
  local file="$1"
  [ -s "$file" ] || return 1
  grep -Eq '"id"[[:space:]]*:[[:space:]]*"'"$LEGACY_COMPONENT_ID"'"' "$file" || return 1
  grep -Eiq 'WuxianPi|yuanshengwuxianpi|127[.]0[.]0[.]1:20765' "$file"
}

migrate_legacy_component_file() {
  local legacy backup_dir backup
  legacy="$(legacy_component_file)"
  [ -e "$legacy" ] || return 0
  if [ -L "$legacy" ]; then
    warn "旧组件文件是符号链接，保留不动：$legacy"
    return 0
  fi
  if ! is_known_legacy_component "$legacy"; then
    warn "旧组件文件不是已知 WuxianPi 清单，保留不动：$legacy"
    return 0
  fi

  backup_dir="${WUXIANPI_COMPONENT_MIGRATION_DIR:-$HOME/.local/share/wuxianpi/plugins/wuxianpi.first-install/migrations}"
  mkdir -p "$backup_dir"
  backup="$backup_dir/$LEGACY_COMPONENT_ID.json"
  if [ -e "$backup" ]; then
    backup="$backup_dir/$LEGACY_COMPONENT_ID.$(date -u +%Y%m%dT%H%M%SZ).$$.json"
  fi
  if cp -p -- "$legacy" "$backup"; then
    log "已备份旧 WuxianPi 组件清单：$backup"
  else
    warn "无法备份旧组件清单，保留原文件：$legacy"
    return 0
  fi
  if rm -f -- "$legacy"; then
    log "已迁移旧组件清单：$legacy -> $backup"
  else
    warn "无法移除旧组件清单，仍会注册新组件：$legacy"
  fi
}

prepare_curl_config() {
  local token="$1"
  local config_file="$2"
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$config_file"
  chmod 600 "$config_file"
}

api_request() {
  local method="$1"
  local path="$2"
  local curl_config="$3"
  local body_file="${4:-}"
  local url
  url="$(service_manager_url)${path}"
  if [ -n "$body_file" ]; then
    curl -q -fsS --max-time 10 -K "$curl_config" \
      -H 'Content-Type: application/json' -X "$method" --data-binary "@$body_file" "$url"
  else
    curl -q -fsS --max-time 10 -K "$curl_config" -X "$method" "$url"
  fi
}

remove_legacy_registry_entry() {
  local curl_config="$1"
  if api_request DELETE "/api/v1/registry/components/$LEGACY_COMPONENT_ID" "$curl_config" >/dev/null 2>&1; then
    log "已清理旧 registry 组件：$LEGACY_COMPONENT_ID"
  else
    warn "旧 registry 组件清理失败，继续注册 $COMPONENT_ID。"
  fi
}

write_file_fallback() {
  local target_dir="$HOME/.config/openhouseai/components.d"
  local target="$target_dir/$COMPONENT_ID.json"
  local temp
  mkdir -p "$target_dir"
  temp="$(mktemp "$target_dir/.$COMPONENT_ID.json.XXXXXX")"
  write_component_manifest "$temp"
  if [ -f "$target" ] && cmp -s "$temp" "$target"; then
    rm -f "$temp"
    log "文件 registry 已是最新：$target"
    return 0
  fi
  chmod 600 "$temp"
  mv -f "$temp" "$target"
  log "已写入文件 registry：$target"
}

register_component() {
  local token curl_config work_dir manifest response
  REGISTRY_WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/wuxianpi-openhouse-registry.XXXXXX")"
  work_dir="$REGISTRY_WORK_DIR"
  trap cleanup_registry_work_dir EXIT INT HUP TERM
  manifest="$work_dir/$COMPONENT_ID.json"
  curl_config="$work_dir/curl.cfg"
  write_component_manifest "$manifest"
  migrate_legacy_component_file

  if ! token="$(service_manager_token)"; then
    write_file_fallback
    warn "无法读取 service-manager token；已保留文件 registry，稍后可重新运行 register。"
    return 0
  fi
  prepare_curl_config "$token" "$curl_config"

  if ! response="$(api_request GET "/api/v1/services/$SERVICE_ID" "$curl_config" 2>/dev/null)"; then
    write_file_fallback
    warn "无法读取已安装的 $SERVICE_ID；已保留文件 registry。"
    return 0
  fi
  printf '%s' "$response" | grep -Eq '"(service_id|serviceId|id|name)"[[:space:]]*:[[:space:]]*"'"$SERVICE_ID"'"' \
    || die "service-manager 中未找到已安装服务：$SERVICE_ID"

  remove_legacy_registry_entry "$curl_config"
  if api_request PUT "/api/v1/registry/components/$COMPONENT_ID" "$curl_config" "$manifest" >/dev/null; then
    if ! api_request POST "/api/v1/registry/sync" "$curl_config" >/dev/null 2>&1; then
      warn "registry API 写入成功，但 sync 暂时失败；保留文件 registry，下一次运行会重试。"
    fi
    write_file_fallback
    log "已通过 service-manager 注册组件：$COMPONENT_ID"
    return 0
  fi

  write_file_fallback
  warn "registry API 写入失败；已保留文件 registry，稍后可重新运行 register。"
}

verify_component() {
  local token curl_config work_dir body component_file
  component_file="$HOME/.config/openhouseai/components.d/$COMPONENT_ID.json"
  migrate_legacy_component_file
  [ -s "$component_file" ] || die "桌面组件清单不存在：$component_file"
  grep -Eq '"id"[[:space:]]*:[[:space:]]*"'"$COMPONENT_ID"'"' "$component_file" \
    || die "桌面组件清单 ID 不正确：$component_file"

  if ! token="$(service_manager_token)"; then
    warn "无法读取 service-manager token；文件 registry 已存在，但 API 尚未验证。"
    printf 'registry_component=%s\nregistry_file=ok\nregistry_api=unverified\n' "$COMPONENT_ID"
    return 0
  fi
  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/wuxianpi-openhouse-verify.XXXXXX")"
  REGISTRY_WORK_DIR="$work_dir"
  trap cleanup_registry_work_dir EXIT INT HUP TERM
  curl_config="$work_dir/curl.cfg"
  prepare_curl_config "$token" "$curl_config"
  if ! body="$(api_request GET /api/v1/registry/components "$curl_config" 2>/dev/null)"; then
    warn "无法读取 service-manager registry API；文件 registry 已存在，稍后可重新运行 verify。"
    printf 'registry_component=%s\nregistry_file=ok\nregistry_api=unverified\n' "$COMPONENT_ID"
    return 0
  fi
  printf '%s' "$body" | grep -Eq '"id"[[:space:]]*:[[:space:]]*"'"$COMPONENT_ID"'"' \
    || die "registry API 中没有组件：$COMPONENT_ID"
  printf 'registry_component=%s\nregistry_file=ok\nregistry_api=ok\nregistry_url=%s\n' "$COMPONENT_ID" "$COMPONENT_URL"
}

case "${1:-}" in
  register) register_component ;;
  verify|status) verify_component ;;
  *) die "用法：$0 register|verify" ;;
esac
