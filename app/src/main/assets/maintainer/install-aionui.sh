payload_dir="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}"
payload="$payload_dir/aionui-web-2.1.25-linux-arm64.tgz"
expected_sha256="e9cf83bf0776c8d89765933a56dd7d98329926afae0748ece49b68de7fc447ad"
expected_size="357095493"
default_install_dir="/root/.local/share/openhouseai/aionui-web-versions/2.1.25"

if [ ! -f "$payload" ]; then
  log "未找到 APK 内置 AionUi 离线包：$payload"
  exit 1
fi

log "正在安装 AionUi 离线工作台。"
require_ubuntu

run_ubuntu_logged env \
  AIONUI_PAYLOAD_PATH="$payload" \
  AIONUI_EXPECTED_SHA256="$expected_sha256" \
  AIONUI_EXPECTED_SIZE="$expected_size" \
  AIONUI_INSTALL_DIR="${OPENHOUSE_AIONUI_INSTALL_DIR:-$default_install_dir}" \
  AIONUI_INSTALL_DIR_EXPLICIT="${OPENHOUSE_AIONUI_INSTALL_DIR:+1}" \
  AIONUI_PORT="${AIONUI_PORT:-__AIONUI_WEB_PORT__}" \
  AIONUI_DATA_DIR="${AIONUI_DATA_DIR:-/root/.aionui-web}" \
  AIONUI_LOG_DIR="${AIONUI_LOG_DIR:-/root/.aionui-web/logs}" \
  AIONUI_OPEN_BROWSER=0 \
  bash <<'AIONUI_INSTALL'
set -euo pipefail

payload="${AIONUI_PAYLOAD_PATH:?missing AIONUI_PAYLOAD_PATH}"
expected_sha256="${AIONUI_EXPECTED_SHA256:?missing AIONUI_EXPECTED_SHA256}"
expected_size="${AIONUI_EXPECTED_SIZE:?missing AIONUI_EXPECTED_SIZE}"
default_install_dir="/root/.local/share/openhouseai/aionui-web-versions/2.1.25"
install_dir="${AIONUI_INSTALL_DIR:-$default_install_dir}"
install_dir_explicit="${AIONUI_INSTALL_DIR_EXPLICIT:-0}"
port="${AIONUI_PORT:-25808}"
data_dir="${AIONUI_DATA_DIR:-/root/.aionui-web}"
log_dir="${AIONUI_LOG_DIR:-/root/.aionui-web/logs}"
config_dir="${OPENHOUSEAI_CONFIG_DIR:-/root/.config/openhouseai}"
pid_file="$config_dir/aionui.pid"
url_file="$config_dir/aionui-url"
status_file="$config_dir/aionui-status.json"
env_file="$config_dir/aionui.env"
health_url="http://127.0.0.1:$port/"
auth_status_url="http://127.0.0.1:$port/api/auth/status"
install_marker="$install_dir/.openhouse-aionui-payload.sha256"
managed_marker="$install_dir/.openhouse-aionui-managed"
webview_compat_marker="$install_dir/.openhouse-aionui-webview-compat"
webview_compat_version="array-copy-methods-v1"
work_dir="/root/.cache/openhouseai/aionui-install"
stable_install_link="/root/.local/share/openhouseai/aionui-web"
version="2.1.25"
service_id="aionui-web"
wrapper_path="/usr/local/bin/openhouse-aionui-web-start"
service_specs_dir="$config_dir/service-manager/services.d"
components_dir="$config_dir/components.d"
service_spec_file="$service_specs_dir/$service_id.json"
component_file="$components_dir/$service_id.json"
termux_config_dir="/data/data/com.termux/files/home/.config/openhouseai"
termux_service_spec_file="$termux_config_dir/service-manager/services.d/$service_id.json"
termux_component_file="$termux_config_dir/components.d/$service_id.json"
service_ref="service-manager://services/$service_id"

log() {
  printf '[AionUi] %s\n' "$*"
}

warn() {
  printf '[AionUi] WARN: %s\n' "$*" >&2
}

fail() {
  printf '[AionUi] ERROR: %s\n' "$*" >&2
  exit 1
}

case "$port" in
  ""|*[!0-9]*) fail "AionUi 端口无效：$port" ;;
esac
if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
  fail "AionUi 端口超出范围：$port"
fi

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$file" | awk '{print $NF}'
  else
    fail "缺少 sha256 校验工具"
  fi
}

file_size() {
  local file="$1"
  if stat -c '%s' "$file" >/dev/null 2>&1; then
    stat -c '%s' "$file"
  else
    wc -c < "$file" | tr -d ' '
  fi
}

fetch_url() {
  local url="$1"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --max-time 5 "$url" 2>/dev/null
    return $?
  fi
  if command -v wget >/dev/null 2>&1; then
    wget -q -T 5 -O - "$url" 2>/dev/null
    return $?
  fi
  return 127
}

port_accepts_connection() {
  if command -v timeout >/dev/null 2>&1; then
    timeout 2 bash -c ":</dev/tcp/127.0.0.1/$port" >/dev/null 2>&1
    return $?
  fi
  bash -c ":</dev/tcp/127.0.0.1/$port" >/dev/null 2>&1
}

aionui_index_matches() {
  grep -Eiq '<title>[[:space:]]*AionUi[[:space:]]*</title>|<meta[^>]+name=["'\'']application-name["'\''][^>]+content=["'\'']AionUi["'\'']|<meta[^>]+content=["'\'']AionUi["'\''][^>]+name=["'\'']application-name["'\'']'
}

aionui_auth_status_matches() {
  local body
  body="$(cat)"
  if printf '%s' "$body" | grep -Eiq '"needs_setup"[[:space:]]*:' \
    && printf '%s' "$body" | grep -Eiq '"user_count"[[:space:]]*:' \
    && printf '%s' "$body" | grep -Eiq '"is_authenticated"[[:space:]]*:'; then
    return 0
  fi
  if printf '%s' "$body" | grep -Eiq '"success"[[:space:]]*:[[:space:]]*(true|false)' \
    && printf '%s' "$body" | grep -Eiq '"(needs_setup|user_count|is_authenticated)"[[:space:]]*:'; then
    return 0
  fi
  return 1
}

is_aionui_service() {
  local body
  body="$(fetch_url "$health_url" || true)"
  if [ -n "$body" ] && printf '%s' "$body" | aionui_index_matches; then
    return 0
  fi
  body="$(fetch_url "$auth_status_url" || true)"
  if [ -n "$body" ] && printf '%s' "$body" | aionui_auth_status_matches; then
    return 0
  fi
  return 1
}

pid_alive() {
  local pid="${1:-}"
  [ -n "$pid" ] || return 1
  kill -0 "$pid" >/dev/null 2>&1
}

pid_matches_aionui() {
  local pid="${1:-}"
  [ -n "$pid" ] || return 1
  [ -r "/proc/$pid/cmdline" ] || return 1
  tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q 'aionui-web'
}

read_pid() {
  if [ -f "$pid_file" ]; then
    sed -n '1p' "$pid_file" 2>/dev/null | tr -cd '0-9'
  fi
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

shell_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

write_file_from_stdin() {
  local target="$1"
  local mode="${2:-0644}"
  local dir
  local tmp

  dir="$(dirname "$target")"
  mkdir -p "$dir" || return 1
  tmp="$target.tmp.$$"
  if ! cat > "$tmp"; then
    rm -f "$tmp" >/dev/null 2>&1 || true
    return 1
  fi
  chmod "$mode" "$tmp" >/dev/null 2>&1 || true
  if ! mv "$tmp" "$target"; then
    rm -f "$tmp" >/dev/null 2>&1 || true
    return 1
  fi
}

sync_termux_registry_file() {
  local source="$1"
  local target="$2"
  local dir
  local tmp

  if [ ! -d "/data/data/com.termux/files/home" ]; then
    warn "当前 Ubuntu/proot 环境未暴露 Termux home，跳过同步：$target"
    return 1
  fi
  if [ -e "$target" ] && [ ! -f "$target" ]; then
    warn "Termux canonical 目标不是普通文件，跳过以避免覆盖用户目录：$target"
    return 1
  fi

  dir="$(dirname "$target")"
  if ! mkdir -p "$dir"; then
    warn "无法创建 Termux canonical registry 目录：$dir"
    return 1
  fi

  tmp="$target.tmp.$$"
  if ! cp "$source" "$tmp"; then
    warn "无法写入 Termux canonical registry 临时文件：$tmp"
    rm -f "$tmp" >/dev/null 2>&1 || true
    return 1
  fi
  chmod 0644 "$tmp" >/dev/null 2>&1 || true
  if ! mv "$tmp" "$target"; then
    warn "无法更新 Termux canonical registry 文件：$target"
    rm -f "$tmp" >/dev/null 2>&1 || true
    return 1
  fi
}

read_openhouse_service_manager_endpoint() {
  local config
  local key
  local value

  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "$termux_config_dir/service-manager/config.json"; do
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
  local endpoint
  local scheme
  local bind

  endpoint="$(read_openhouse_service_manager_endpoint || true)"
  [ -n "$endpoint" ] || endpoint="${SERVICE_MANAGER_URL:-}"
  [ -n "$endpoint" ] || endpoint="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-}"
  case "$endpoint" in
    https://*) scheme="https" ;;
    *) scheme="http" ;;
  esac
  bind="$(normalize_service_manager_bind "${endpoint:-127.0.0.1:20087}")" || bind="127.0.0.1:20087"
  printf '%s://%s\n' "$scheme" "$bind"
}

probe_url() {
  command -v curl >/dev/null 2>&1 || return 1
  curl -q -fsS --max-time 3 "$1" >/dev/null 2>&1
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
    "$HOME/smallphoneai-repos/service-manager/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/target/release/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/target/debug/service-manager" \
    "/root/projects/service-manager/service-manager" \
    "/root/projects/service-manager/target/release/service-manager" \
    "/root/projects/service-manager/target/debug/service-manager"; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

read_service_manager_token_from_config() {
  local config
  local token

  service_manager_config_candidates | while IFS= read -r config; do
    [ -n "$config" ] && [ -f "$config" ] || continue
    token="$(read_service_manager_token_from_config_file "$config" || true)"
    if [ -n "$token" ]; then
      printf '%s' "$token"
      return 0
    fi
  done
}

service_manager_config_candidates() {
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "$termux_config_dir/service-manager/config.json" \
    "$HOME/.config/service-manager/config.json" \
    "/data/data/com.termux/files/home/.config/service-manager/config.json"; do
    [ -n "$config" ] || continue
    printf '%s\n' "$config"
  done
}

read_service_manager_token_from_config_file() {
  local config="$1"
  [ -f "$config" ] || return 1
  sed -n 's/.*"auth_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$config" | head -n 1
}

token_auth_ready_or_unknown() {
  local token="$1"
  if command -v curl >/dev/null 2>&1; then
    service_manager_auth_ready "$token"
    return $?
  fi
  [ -n "$token" ]
}

resolve_service_manager_token() {
  local bin="${1:-}"
  local token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  local config
  local fallback_token=""

  if [ -n "$token" ]; then
    fallback_token="$token"
    if token_auth_ready_or_unknown "$token"; then
      printf '%s' "$token"
      return 0
    fi
  fi

  while IFS= read -r config; do
    [ -n "$config" ] && [ -f "$config" ] || continue
    token="$(read_service_manager_token_from_config_file "$config" | tr -d '\r\n' || true)"
    [ -n "$token" ] || continue
    [ -n "$fallback_token" ] || fallback_token="$token"
    if token_auth_ready_or_unknown "$token"; then
      printf '%s' "$token"
      return 0
    fi
  done <<EOF
$(service_manager_config_candidates)
EOF

  if [ -n "$bin" ]; then
    while IFS= read -r config; do
      [ -n "$config" ] && [ -f "$config" ] || continue
      token="$("$bin" token show --config "$config" 2>/dev/null | head -n 1 | tr -d '\r\n' || true)"
      [ -n "$token" ] || continue
      [ -n "$fallback_token" ] || fallback_token="$token"
      if token_auth_ready_or_unknown "$token"; then
        printf '%s' "$token"
        return 0
      fi
    done <<EOF
$(service_manager_config_candidates)
EOF

    token="$("$bin" token show 2>/dev/null | head -n 1 | tr -d '\r\n' || true)"
    if [ -n "$token" ] && [ -z "$fallback_token" ]; then
      fallback_token="$token"
    fi
    if token_auth_ready_or_unknown "$token"; then
      printf '%s' "$token"
      return 0
    fi
  fi

  printf '%s' "$fallback_token"
}

write_curl_auth_config() {
  local target="$1"
  local token="$2"

  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$target"
}

service_manager_auth_ready() {
  local token="$1"
  local tmp_dir
  local curl_cfg
  local status

  [ -n "$token" ] || return 1
  command -v curl >/dev/null 2>&1 || return 1
  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-aionui-auth.XXXXXX")" || return 1
  curl_cfg="$tmp_dir/curl.cfg"
  write_curl_auth_config "$curl_cfg" "$token"
  curl -q -fsS --max-time 5 -K "$curl_cfg" "$sm_url/api/v1/services" >/dev/null 2>&1
  status=$?
  rm -rf "$tmp_dir" >/dev/null 2>&1 || true
  return "$status"
}

service_manager_post() {
  local token="$1"
  local path="$2"
  local body_file="${3:-}"
  local tmp_dir
  local curl_cfg
  local status

  command -v curl >/dev/null 2>&1 || fail "缺少 curl，无法调用 service-manager API"
  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-aionui-api.XXXXXX")" || fail "无法创建 service-manager API 临时目录"
  curl_cfg="$tmp_dir/curl.cfg"
  write_curl_auth_config "$curl_cfg" "$token"
  if [ -n "$body_file" ]; then
    curl -q -fsS --max-time 20 -X POST -K "$curl_cfg" \
      -H 'Content-Type: application/json' \
      --data-binary "@$body_file" \
      "$sm_url$path" >/dev/null
    status=$?
  else
    curl -q -fsS --max-time 20 -X POST -K "$curl_cfg" "$sm_url$path" >/dev/null
    status=$?
  fi
  rm -rf "$tmp_dir" >/dev/null 2>&1 || true
  return "$status"
}

service_manager_get() {
  local token="$1"
  local path="$2"
  local tmp_dir
  local curl_cfg
  local status

  command -v curl >/dev/null 2>&1 || fail "缺少 curl，无法调用 service-manager API"
  tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-aionui-api.XXXXXX")" || fail "无法创建 service-manager API 临时目录"
  curl_cfg="$tmp_dir/curl.cfg"
  write_curl_auth_config "$curl_cfg" "$token"
  curl -q -fsS --max-time 10 -K "$curl_cfg" "$sm_url$path"
  status=$?
  rm -rf "$tmp_dir" >/dev/null 2>&1 || true
  return "$status"
}

service_manager_service_running() {
  local token="$1"
  local body

  body="$(service_manager_get "$token" "/api/v1/services/$service_id/status" 2>/dev/null || true)"
  [ -n "$body" ] || return 1
  printf '%s' "$body" | grep -Eq '"state"[[:space:]]*:[[:space:]]*"running"'
}

dir_has_content() {
  local dir="$1"
  [ -d "$dir" ] || return 1
  [ -n "$(find "$dir" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]
}

dir_is_managed() {
  [ -f "$install_marker" ] || [ -f "$managed_marker" ]
}

ensure_install_dir_safe_to_replace() {
  if [ -e "$install_dir" ] && [ ! -d "$install_dir" ]; then
    fail "安装路径已存在但不是目录：$install_dir"
  fi
  if [ -d "$install_dir" ] && dir_has_content "$install_dir" && ! dir_is_managed; then
    if [ "$install_dir_explicit" = "1" ]; then
      fail "OPENHOUSE_AIONUI_INSTALL_DIR 指向已有非本安装器管理目录，已停止以避免覆盖：$install_dir"
    fi
    fail "AionUi 安装目录已有非本安装器管理内容，已停止以避免覆盖：$install_dir"
  fi
}

refresh_stable_install_link() {
  [ "$install_dir_explicit" = "1" ] && return 0
  [ "$install_dir" != "$stable_install_link" ] || return 0
  mkdir -p "$(dirname "$stable_install_link")"
  if [ -L "$stable_install_link" ] || [ ! -e "$stable_install_link" ]; then
    ln -sfn "$install_dir" "$stable_install_link"
  else
    log "稳定安装路径已有内容，跳过链接：$stable_install_link"
  fi
}

ensure_command_link_safe() {
  local command_path="/usr/local/bin/aionui-web"
  local managed_prefix="/root/.local/share/openhouseai/aionui-web-versions/"
  local link_target=""
  local resolved_target=""

  mkdir -p "$(dirname "$command_path")"
  if [ ! -e "$command_path" ] && [ ! -L "$command_path" ]; then
    ln -s "$install_dir/aionui-web" "$command_path"
    return 0
  fi

  if [ -L "$command_path" ]; then
    link_target="$(readlink "$command_path" 2>/dev/null || true)"
    resolved_target="$(readlink -f "$command_path" 2>/dev/null || true)"
    case "$link_target" in
      "$managed_prefix"*) ln -sfn "$install_dir/aionui-web" "$command_path"; return 0 ;;
    esac
    case "$resolved_target" in
      "$managed_prefix"*) ln -sfn "$install_dir/aionui-web" "$command_path"; return 0 ;;
    esac
  fi

  fail "检测到已有手工 AionUi 命令入口：$command_path；请先由用户处理该入口后重试，安装器不会覆盖。"
}

write_aionui_wrapper() {
  write_file_from_stdin "$wrapper_path" 0755 <<EOF
#!/bin/sh
set -eu

# Managed by OpenHouseAI. This wrapper must end by exec-ing AionUi so
# service-manager can track the foreground process it owns.
install_dir=\${OPENHOUSE_AIONUI_INSTALL_DIR:-$(shell_quote "$install_dir")}
port=\${AIONUI_PORT:-$(shell_quote "$port")}
data_dir=\${AIONUI_DATA_DIR:-$(shell_quote "$data_dir")}
log_dir=\${AIONUI_LOG_DIR:-$(shell_quote "$log_dir")}

cd "\$install_dir"
export AIONUI_PORT="\$port"
export AIONUI_DATA_DIR="\$data_dir"
export AIONUI_LOG_DIR="\$log_dir"
export AIONUI_OPEN_BROWSER=0
export AIONUI_BUNDLED_AIONCORE_DIR="\${AIONUI_BUNDLED_AIONCORE_DIR:-\$install_dir/bundled-aioncore/linux-arm64}"
export AIONCORE_BIN="\${AIONCORE_BIN:-\$install_dir/bundled-aioncore/linux-arm64/aioncore}"
exec "\$install_dir/aionui-web" start --port "\$port" --data-dir "\$data_dir"
EOF
}

write_service_spec() {
  local install_json
  local data_json
  local log_json
  local health_json
  local core_dir_json
  local core_bin_json
  local wrapper_json

  install_json="$(json_escape "$install_dir")"
  data_json="$(json_escape "$data_dir")"
  log_json="$(json_escape "$log_dir")"
  health_json="$(json_escape "$health_url")"
  core_dir_json="$(json_escape "$install_dir/bundled-aioncore/linux-arm64")"
  core_bin_json="$(json_escape "$install_dir/bundled-aioncore/linux-arm64/aioncore")"
  wrapper_json="$(json_escape "$wrapper_path")"

  write_file_from_stdin "$service_spec_file" 0644 <<EOF
{
  "name": "$service_id",
  "description": "AionUi local AI workspace",
  "provider": "process",
  "command": ["sh", "-lc", "openhouse-aionui-web-start"],
  "working_dir": "$install_json",
  "env": {
    "OPENHOUSE_AIONUI_INSTALL_DIR": "$install_json",
    "AIONUI_PORT": "$port",
    "AIONUI_DATA_DIR": "$data_json",
    "AIONUI_LOG_DIR": "$log_json",
    "AIONUI_OPEN_BROWSER": "0",
    "AIONUI_BUNDLED_AIONCORE_DIR": "$core_dir_json",
    "AIONCORE_BIN": "$core_bin_json",
    "OPENHOUSE_AIONUI_WRAPPER": "$wrapper_json"
  },
  "runtime": {},
  "restart": {
    "mode": "on-failure",
    "max_retries": 3
  },
  "health": [
    {
      "type": "http",
      "url": "$health_json",
      "interval": "5s",
      "timeout": "3s"
    }
  ],
  "enabled": true,
  "tags": [
    "group:local-stack",
    "openhouseai",
    "openhouse-component:aionui-web",
    "aionui-web"
  ]
}
EOF
}

write_component_manifest() {
  local health_json
  local service_ref_json

  health_json="$(json_escape "$health_url")"
  service_ref_json="$(json_escape "$service_ref")"

  write_file_from_stdin "$component_file" 0644 <<EOF
{
  "schemaVersion": 1,
  "id": "$service_id",
  "title": "AionUi",
  "enabled": true,
  "shellMenu": {
    "title": "AionUi",
    "subtitle": "本地 AI 工作台",
    "section": "ai",
    "order": 30,
    "visible": true,
    "favorite": true,
    "entry": {
      "type": "webview",
      "url": "$health_json"
    },
    "controlEntry": {
      "type": "service-control",
      "title": "控制",
      "serviceRef": "$service_ref_json",
      "serviceRefs": [
        "$service_ref_json"
      ]
    }
  },
  "smallphoneApp": {},
  "serviceManager": {
    "services": [
      {
        "name": "$service_id",
        "serviceRef": "$service_ref_json"
      }
    ]
  },
  "ai": {}
}
EOF
}

sync_termux_registry_files() {
  sync_termux_registry_file "$service_spec_file" "$termux_service_spec_file" || true
  sync_termux_registry_file "$component_file" "$termux_component_file" || true
}

write_registry_apply_payload() {
  local target="$1"

  {
    printf '{\n  "component": '
    cat "$component_file"
    printf ',\n  "services": [\n    {\n      "id": "%s",\n      "service": ' "$(json_escape "$service_id")"
    cat "$service_spec_file"
    printf '\n    }\n  ]\n}\n'
  } > "$target"
}

stop_legacy_aionui_pid() {
  local existing_pid
  local waited

  existing_pid="$(read_pid || true)"
  if pid_alive "$existing_pid" && pid_matches_aionui "$existing_pid"; then
    log "停止旧版直接后台 AionUi 进程：pid=$existing_pid"
    kill "$existing_pid" >/dev/null 2>&1 || true
    waited=0
    while pid_alive "$existing_pid" && [ "$waited" -lt 5 ]; do
      sleep 1
      waited=$((waited + 1))
    done
    if pid_alive "$existing_pid"; then
      warn "旧版 AionUi 进程未响应 SIGTERM，尝试 SIGKILL：pid=$existing_pid"
      kill -KILL "$existing_pid" >/dev/null 2>&1 || true
      sleep 1
    fi
    rm -f "$pid_file" >/dev/null 2>&1 || true
    return 0
  fi

  if pid_alive "$existing_pid"; then
    warn "忽略非 AionUi 的旧 pid：$existing_pid"
  else
    rm -f "$pid_file" >/dev/null 2>&1 || true
  fi
}

apply_and_start_service_manager_service() {
  local token="$1"
  local api_dir
  local apply_payload

  api_dir="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-aionui-apply.XXXXXX")" || fail "无法创建 registry apply 临时目录"
  apply_payload="$api_dir/registry-apply.json"
  write_registry_apply_payload "$apply_payload"

  log "正在通过 service-manager 应用 AionUi registry。"
  service_manager_post "$token" "/api/v1/registry/apply" "$apply_payload" \
    || {
      rm -rf "$api_dir" >/dev/null 2>&1 || true
      fail "service-manager registry apply 失败：$sm_url/api/v1/registry/apply"
    }
  rm -rf "$api_dir" >/dev/null 2>&1 || true

  log "正在注册 service-manager 服务：$service_id"
  service_manager_post "$token" "/api/v1/services/$service_id/register" \
    || fail "service-manager 服务注册失败：$service_id"

  log "正在由 service-manager 启动 AionUi：$health_url"
  service_manager_post "$token" "/api/v1/services/$service_id/start" \
    || fail "service-manager 服务启动失败：$service_id"
}

wait_for_aionui_service() {
  local token="$1"
  local stable=0
  local attempt

  for attempt in $(seq 1 60); do
    if service_manager_service_running "$token" && is_aionui_service; then
      stable=$((stable + 1))
      if [ "$stable" -ge 2 ]; then
        return 0
      fi
    else
      stable=0
    fi
    sleep 2
  done

  return 1
}

write_status() {
  local reachable="$1"
  local pid="${2:-}"
  mkdir -p "$config_dir"
  printf '%s\n' "$health_url" > "$url_file"
  {
    printf 'AIONUI_URL=%s\n' "$health_url"
    printf 'AIONUI_PORT=%s\n' "$port"
    printf 'AIONUI_INSTALL_DIR=%s\n' "$install_dir"
    printf 'AIONUI_DATA_DIR=%s\n' "$data_dir"
    printf 'AIONUI_LOG_DIR=%s\n' "$log_dir"
  } > "$env_file"
  printf '{"installed":true,"reachable":%s,"url":"%s","port":%s,"pid":"%s","installDir":"%s","version":"%s"}\n' \
    "$reachable" \
    "$(json_escape "$health_url")" \
    "$port" \
    "$(json_escape "$pid")" \
    "$(json_escape "$install_dir")" \
    "$(json_escape "$version")" > "$status_file"
}

apply_webview_compat_patch() {
  local static_dir="$install_dir/static"
  local assets_dir="$static_dir/assets"
  local index_file="$static_dir/index.html"
  local compat_file="$assets_dir/openhouse-webview-compat.js"
  local tmp_index

  [ -f "$index_file" ] || fail "AionUi 静态入口不可用：$index_file"
  mkdir -p "$assets_dir"
  cat > "$compat_file" <<'EOF'
(function () {
  function defineArrayCopyMethod(name, implementation) {
    if (typeof Array.prototype[name] === 'function') return;
    Object.defineProperty(Array.prototype, name, {
      value: implementation,
      writable: true,
      configurable: true
    });
  }

  defineArrayCopyMethod('toReversed', function () {
    return Array.prototype.slice.call(this).reverse();
  });

  defineArrayCopyMethod('toSorted', function (compareFn) {
    return Array.prototype.slice.call(this).sort(compareFn);
  });

  defineArrayCopyMethod('toSpliced', function () {
    var copy = Array.prototype.slice.call(this);
    Array.prototype.splice.apply(copy, arguments);
    return copy;
  });

  defineArrayCopyMethod('with', function (index, value) {
    var copy = Array.prototype.slice.call(this);
    var length = copy.length;
    var offset = Number(index);
    if (offset < 0) offset = length + offset;
    if (offset < 0 || offset >= length) throw new RangeError('Invalid array index');
    copy[offset] = value;
    return copy;
  });
})();
EOF

  if ! grep -q 'openhouse-webview-compat.js' "$index_file"; then
    tmp_index="$index_file.tmp.$$"
    if ! awk '
      /<script type="module"/ && !inserted {
        print "    <script src=\"./assets/openhouse-webview-compat.js\"></script>";
        inserted = 1;
      }
      { print }
      END { if (!inserted) exit 2 }
    ' "$index_file" > "$tmp_index"; then
      rm -f "$tmp_index"
      fail "无法向 AionUi index.html 注入 WebView 兼容脚本"
    fi
    mv "$tmp_index" "$index_file"
  fi

  printf '%s\n' "$webview_compat_version" > "$webview_compat_marker"
}

[ -f "$payload" ] || fail "离线包不存在：$payload"
actual_size="$(file_size "$payload")"
[ "$actual_size" = "$expected_size" ] || fail "离线包大小不匹配：expected=$expected_size actual=$actual_size"
actual_sha256="$(sha256_file "$payload")"
[ "$actual_sha256" = "$expected_sha256" ] || fail "离线包 sha256 不匹配：expected=$expected_sha256 actual=$actual_sha256"
log "离线包校验通过：$actual_sha256"

needs_extract=1
if [ -x "$install_dir/aionui-web" ] \
  && [ -f "$install_dir/static/index.html" ] \
  && [ -f "$install_marker" ] \
  && [ "$(sed -n '1p' "$install_marker" 2>/dev/null || true)" = "$expected_sha256" ]; then
  needs_extract=0
fi

if [ "$needs_extract" = "1" ]; then
  ensure_install_dir_safe_to_replace
  log "正在解压 AionUi 到 $install_dir"
  rm -rf "$work_dir"
  install_parent="$(dirname "$install_dir")"
  install_base="$(basename "$install_dir")"
  tmp_install_dir="$install_parent/.$install_base.openhouse-tmp.$$"
  rm -rf "$tmp_install_dir"
  mkdir -p "$work_dir" "$install_parent"
  tar --no-same-owner -xzf "$payload" -C "$work_dir"
  [ -x "$work_dir/aionui-web/aionui-web" ] || fail "离线包缺少可执行文件"
  [ -f "$work_dir/aionui-web/static/index.html" ] || fail "离线包缺少 Web 静态资源"
  [ -x "$work_dir/aionui-web/bundled-aioncore/linux-arm64/aioncore" ] || fail "离线包缺少内置 AionCore"
  chmod +x "$work_dir/aionui-web/aionui-web"
  chmod +x "$work_dir/aionui-web/bundled-aioncore/linux-arm64/aioncore"
  mv "$work_dir/aionui-web" "$tmp_install_dir"
  printf '%s\n' "$expected_sha256" > "$tmp_install_dir/.openhouse-aionui-payload.sha256"
  {
    printf 'managed_by=openhouseai-apk\n'
    printf 'version=%s\n' "$version"
    printf 'payload_sha256=%s\n' "$expected_sha256"
  } > "$tmp_install_dir/.openhouse-aionui-managed"
  rm -rf "$install_dir"
  mv "$tmp_install_dir" "$install_dir"
  rm -rf "$work_dir"
else
  log "AionUi 已安装，跳过解压。"
fi

[ -x "$install_dir/aionui-web" ] || fail "AionUi 可执行文件不可用：$install_dir/aionui-web"
[ -f "$install_dir/static/index.html" ] || fail "AionUi 静态资源不可用：$install_dir/static/index.html"
[ -x "$install_dir/bundled-aioncore/linux-arm64/aioncore" ] || fail "AionUi 内置 AionCore 不可用：$install_dir/bundled-aioncore/linux-arm64/aioncore"
apply_webview_compat_patch

mkdir -p "$config_dir" "$data_dir" "$log_dir"
ensure_command_link_safe
refresh_stable_install_link
write_aionui_wrapper
write_service_spec
write_component_manifest
sync_termux_registry_files
stop_legacy_aionui_pid

aionui_preexisting=0
if port_accepts_connection; then
  if is_aionui_service; then
    aionui_preexisting=1
    log "AionUi 本机入口已可访问，将刷新 service-manager 注册：$health_url"
  else
    write_status false ""
    fail "端口 $port 已被其他服务占用，且未识别为 AionUi；请更换 AIONUI_PORT 或停止占用服务后重试。"
  fi
fi

sm_url="$(configured_service_manager_url)"
service_manager_bin="$(find_service_manager || true)"
if [ -n "$service_manager_bin" ]; then
  export PATH="$(dirname "$service_manager_bin"):$PATH"
fi

if ! command -v curl >/dev/null 2>&1; then
  write_status false ""
  fail "缺少 curl，无法调用 service-manager；AionUi 不会回退为直接后台启动。"
fi

if ! service_manager_ready; then
  write_status false ""
  fail "service-manager 不可达：$sm_url；AionUi 已安装但未直接后台启动。请先修复控制中枢后重试。"
fi

sm_token="$(resolve_service_manager_token "$service_manager_bin")"
if ! service_manager_auth_ready "$sm_token"; then
  write_status false ""
  fail "service-manager token 不可用或与当前实例不匹配；AionUi 不会回退为直接后台启动。"
fi

if [ "$aionui_preexisting" = "1" ] && ! service_manager_service_running "$sm_token"; then
  write_status false ""
  fail "端口 $port 上已有 AionUi，但 service-manager 未显示 $service_id 处于 running；拒绝把未托管进程当作首装成功。"
fi

apply_and_start_service_manager_service "$sm_token"

if ! wait_for_aionui_service "$sm_token"; then
  write_status false ""
  fail "AionUi 未在 service-manager 托管状态下就绪：$health_url"
fi

write_status true ""
log "AionUi 已由 service-manager 托管并就绪：$health_url"
AIONUI_INSTALL
