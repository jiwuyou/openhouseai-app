payload_dir="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}"
payload="$payload_dir/aionui-web-2.1.32-linux-arm64.tgz"
expected_sha256="f0e368d6cf8ba9c404343143d8b22193012a85c0e62f969b6820e077d137023a"
expected_size="419979294"
default_install_dir="/root/.local/share/openhouseai/aionui-web-versions/2.1.32"

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
default_install_dir="/root/.local/share/openhouseai/aionui-web-versions/2.1.32"
install_dir="${AIONUI_INSTALL_DIR:-$default_install_dir}"
install_dir_explicit="${AIONUI_INSTALL_DIR_EXPLICIT:-0}"
port="${AIONUI_PORT:-25808}"
port_template='{{port:web}}'
data_dir="${AIONUI_DATA_DIR:-/root/.aionui-web}"
log_dir="${AIONUI_LOG_DIR:-/root/.aionui-web/logs}"
config_dir="${OPENHOUSEAI_CONFIG_DIR:-/root/.config/openhouseai}"
pid_file="$config_dir/aionui.pid"
url_file="$config_dir/aionui-url"
status_file="$config_dir/aionui-status.json"
env_file="$config_dir/aionui.env"
preferred_endpoint_url="http://127.0.0.1:$port/"
health_url="http://127.0.0.1:$port/health"
auth_status_url="http://127.0.0.1:$port/api/auth/status"
install_marker="$install_dir/.openhouse-aionui-payload.sha256"
managed_marker="$install_dir/.openhouse-aionui-managed"
webview_compat_marker="$install_dir/.openhouse-aionui-webview-compat"
webview_compat_version="array-copy-methods-v1"
work_dir="/root/.cache/openhouseai/aionui-install"
stable_install_link="/root/.local/share/openhouseai/aionui-web"
version="2.1.32"
service_id="aionui-web"
legacy_service_id="aionui"
wrapper_path="/usr/local/bin/openhouse-aionui-web-start"
service_specs_dir="$config_dir/service-manager/services.d"
components_dir="$config_dir/components.d"
service_spec_file="$service_specs_dir/$service_id.json"
component_file="$components_dir/$service_id.json"
termux_config_dir="/data/data/com.termux/files/home/.config/openhouseai"
termux_service_spec_file="$termux_config_dir/service-manager/services.d/$service_id.json"
termux_component_file="$termux_config_dir/components.d/$service_id.json"
service_ref="service-manager://services/$service_id"
service_manager_post_http_code="000"
service_manager_post_curl_status=0

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
  mkdir -p "$dir" || fail "无法创建临时目录：$dir"
  printf '%s\n' "$dir"
}

openhouse_mktemp_dir() {
  local template="$1"
  local parent
  parent="$(openhouse_tmp_parent)"
  mktemp -d "$parent/$template"
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

summarize_response_file() {
  local file="$1"
  [ -s "$file" ] || return 0
  tr '\r\n' '  ' < "$file" \
    | sed 's/[[:space:]][[:space:]]*/ /g; s/^ //; s/ $//' \
    | cut -c 1-320
}

service_manager_auth_ready() {
  local token="$1"
  local tmp_dir
  local curl_cfg
  local status

  [ -n "$token" ] || return 1
  command -v curl >/dev/null 2>&1 || return 1
  tmp_dir="$(openhouse_mktemp_dir "openhouse-aionui-auth.XXXXXX")" || return 1
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
  local response_file
  local http_code
  local curl_status=0
  local summary

  service_manager_post_http_code="000"
  service_manager_post_curl_status=0
  command -v curl >/dev/null 2>&1 || fail "缺少 curl，无法调用 service-manager API"
  tmp_dir="$(openhouse_mktemp_dir "openhouse-aionui-api.XXXXXX")" || fail "无法创建 service-manager API 临时目录"
  curl_cfg="$tmp_dir/curl.cfg"
  response_file="$tmp_dir/response.txt"
  write_curl_auth_config "$curl_cfg" "$token"
  if [ -n "$body_file" ]; then
    http_code="$(curl -q -sS --max-time 20 -o "$response_file" -w '%{http_code}' -X POST -K "$curl_cfg" \
      -H 'Content-Type: application/json' \
      --data-binary "@$body_file" \
      "$sm_url$path" 2>"$tmp_dir/curl.err")" || curl_status=$?
  else
    http_code="$(curl -q -sS --max-time 20 -o "$response_file" -w '%{http_code}' -X POST -K "$curl_cfg" "$sm_url$path" 2>"$tmp_dir/curl.err")" || curl_status=$?
  fi
  case "$http_code" in
    ""|*[!0-9]*) http_code="000" ;;
  esac
  service_manager_post_http_code="$http_code"
  service_manager_post_curl_status="$curl_status"
  summary="$(summarize_response_file "$response_file" || true)"
  if [ "$curl_status" -eq 0 ] && [ "$http_code" -ge 200 ] && [ "$http_code" -lt 300 ]; then
    log "service-manager POST $path -> HTTP $http_code${summary:+; $summary}"
    rm -rf "$tmp_dir" >/dev/null 2>&1 || true
    return 0
  fi
  if [ -z "$summary" ] && [ -s "$tmp_dir/curl.err" ]; then
    summary="$(summarize_response_file "$tmp_dir/curl.err" || true)"
  fi
  warn "service-manager POST $path -> HTTP ${http_code:-000}, curl=$curl_status${summary:+; $summary}"
  rm -rf "$tmp_dir" >/dev/null 2>&1 || true
  return 1
}

service_manager_start_request() {
  local token="$1"
  local path="/api/v1/services/$service_id/start"

  if service_manager_post "$token" "$path"; then
    return 0
  fi

  case "$service_manager_post_http_code" in
    4??|5??)
      warn "service-manager 明确拒绝 AionUi 启动请求：HTTP $service_manager_post_http_code。"
      return 1
      ;;
  esac

  case "$service_manager_post_curl_status" in
    18|28|52|55|56)
      warn "AionUi 启动请求的传输结果不确定：HTTP $service_manager_post_http_code, curl=$service_manager_post_curl_status；继续通过 service state、endpoint 与健康检查确认最终结果。"
      return 0
      ;;
  esac

  return 1
}

service_manager_put() {
  local token="$1"
  local path="$2"
  local body_file="$3"
  local tmp_dir
  local curl_cfg
  local response_file
  local http_code
  local curl_status=0
  local summary

  command -v curl >/dev/null 2>&1 || fail "缺少 curl，无法调用 service-manager API"
  tmp_dir="$(openhouse_mktemp_dir "openhouse-aionui-api.XXXXXX")" || fail "无法创建 service-manager API 临时目录"
  curl_cfg="$tmp_dir/curl.cfg"
  response_file="$tmp_dir/response.txt"
  write_curl_auth_config "$curl_cfg" "$token"
  http_code="$(curl -q -sS --max-time 20 -o "$response_file" -w '%{http_code}' -X PUT -K "$curl_cfg" \
    -H 'Content-Type: application/json' \
    --data-binary "@$body_file" \
    "$sm_url$path" 2>"$tmp_dir/curl.err")" || curl_status=$?
  case "$http_code" in
    ""|*[!0-9]*) http_code="000" ;;
  esac
  summary="$(summarize_response_file "$response_file" || true)"
  if [ "$curl_status" -eq 0 ] && [ "$http_code" -ge 200 ] && [ "$http_code" -lt 300 ]; then
    log "service-manager PUT $path -> HTTP $http_code${summary:+; $summary}"
    rm -rf "$tmp_dir" >/dev/null 2>&1 || true
    return 0
  fi
  if [ -z "$summary" ] && [ -s "$tmp_dir/curl.err" ]; then
    summary="$(summarize_response_file "$tmp_dir/curl.err" || true)"
  fi
  warn "service-manager PUT $path -> HTTP ${http_code:-000}, curl=$curl_status${summary:+; $summary}"
  rm -rf "$tmp_dir" >/dev/null 2>&1 || true
  return 1
}

service_manager_delete() {
  local token="$1"
  local path="$2"
  local tmp_dir
  local curl_cfg
  local response_file
  local http_code
  local curl_status=0
  local summary

  command -v curl >/dev/null 2>&1 || fail "缺少 curl，无法调用 service-manager API"
  tmp_dir="$(openhouse_mktemp_dir "openhouse-aionui-api.XXXXXX")" || fail "无法创建 service-manager API 临时目录"
  curl_cfg="$tmp_dir/curl.cfg"
  response_file="$tmp_dir/response.txt"
  write_curl_auth_config "$curl_cfg" "$token"
  http_code="$(curl -q -sS --max-time 20 -o "$response_file" -w '%{http_code}' -X DELETE -K "$curl_cfg" "$sm_url$path" 2>"$tmp_dir/curl.err")" || curl_status=$?
  case "$http_code" in
    ""|*[!0-9]*) http_code="000" ;;
  esac
  summary="$(summarize_response_file "$response_file" || true)"
  if [ "$curl_status" -eq 0 ] && [ "$http_code" -ge 200 ] && [ "$http_code" -lt 300 ]; then
    log "service-manager DELETE $path -> HTTP $http_code${summary:+; $summary}"
    rm -rf "$tmp_dir" >/dev/null 2>&1 || true
    return 0
  fi
  if [ -z "$summary" ] && [ -s "$tmp_dir/curl.err" ]; then
    summary="$(summarize_response_file "$tmp_dir/curl.err" || true)"
  fi
  warn "service-manager DELETE $path -> HTTP ${http_code:-000}, curl=$curl_status${summary:+; $summary}"
  rm -rf "$tmp_dir" >/dev/null 2>&1 || true
  return 1
}

service_manager_get() {
  local token="$1"
  local path="$2"
  local tmp_dir
  local curl_cfg
  local response_file
  local http_code
  local curl_status=0
  local summary

  command -v curl >/dev/null 2>&1 || fail "缺少 curl，无法调用 service-manager API"
  tmp_dir="$(openhouse_mktemp_dir "openhouse-aionui-api.XXXXXX")" || fail "无法创建 service-manager API 临时目录"
  curl_cfg="$tmp_dir/curl.cfg"
  response_file="$tmp_dir/response.txt"
  write_curl_auth_config "$curl_cfg" "$token"
  http_code="$(curl -q -sS --max-time 10 -o "$response_file" -w '%{http_code}' -K "$curl_cfg" "$sm_url$path" 2>"$tmp_dir/curl.err")" || curl_status=$?
  case "$http_code" in
    ""|*[!0-9]*) http_code="000" ;;
  esac
  if [ "$curl_status" -eq 0 ] && [ "$http_code" -ge 200 ] && [ "$http_code" -lt 300 ]; then
    cat "$response_file"
    rm -rf "$tmp_dir" >/dev/null 2>&1 || true
    return 0
  fi
  summary="$(summarize_response_file "$response_file" || true)"
  if [ -z "$summary" ] && [ -s "$tmp_dir/curl.err" ]; then
    summary="$(summarize_response_file "$tmp_dir/curl.err" || true)"
  fi
  warn "service-manager GET $path -> HTTP ${http_code:-000}, curl=$curl_status${summary:+; $summary}"
  rm -rf "$tmp_dir" >/dev/null 2>&1 || true
  return 1
}

service_manager_service_running() {
  local token="$1"
  local body

  body="$(service_manager_get "$token" "/api/v1/services/$service_id/status" 2>/dev/null || true)"
  [ -n "$body" ] || return 1
  printf '%s' "$body" | grep -Eq '"state"[[:space:]]*:[[:space:]]*"running"'
}

service_manager_endpoint_record() {
  local token="$1"
  local body
  local tmp_dir
  local endpoint_file

  body="$(service_manager_get "$token" "/api/v1/services/$service_id/endpoints/web" 2>/dev/null || true)"
  [ -n "$body" ] || return 1

  tmp_dir="$(openhouse_mktemp_dir "openhouse-aionui-endpoint.XXXXXX")" || return 1
  endpoint_file="$tmp_dir/endpoint.json"
  printf '%s' "$body" > "$endpoint_file"
  if ! python3 - "$endpoint_file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    endpoint = json.load(f)
url = str(endpoint.get("url") or "")
port = endpoint.get("port")
status = str(endpoint.get("status") or "")
if status != "healthy" or not url or not isinstance(port, int):
    raise SystemExit(1)
print(f"{url}\t{port}")
PY
  then
    rm -rf "$tmp_dir" >/dev/null 2>&1 || true
    return 1
  fi
  rm -rf "$tmp_dir" >/dev/null 2>&1 || true
}

aionui_endpoint_healthy() {
  local endpoint_url="$1"
  local endpoint_health_url="${endpoint_url%/}/health"

  fetch_url "$endpoint_health_url" >/dev/null 2>&1
}

service_manager_service_resolves() {
  local token="$1"
  service_manager_get "$token" "/api/v1/services/$service_id" >/dev/null 2>&1
}

service_manager_aionui_records() {
  local token="$1"
  local body
  local tmp_dir
  local services_file

  body="$(service_manager_get "$token" "/api/v1/services" 2>/dev/null || true)"
  [ -n "$body" ] || return 1

  tmp_dir="$(openhouse_mktemp_dir "openhouse-aionui-services.XXXXXX")" || return 1
  services_file="$tmp_dir/services.json"
  printf '%s' "$body" > "$services_file"

  if command -v python3 >/dev/null 2>&1; then
    if ! python3 - "$service_id" "$legacy_service_id" "$services_file" <<'PY'
import json
import sys

service_id = sys.argv[1]
legacy_service_id = sys.argv[2]
path = sys.argv[3]
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)
if not isinstance(data, list):
    data = []
known_ids = {service_id, legacy_service_id}
for svc in data:
    if not isinstance(svc, dict):
        continue
    sid = str(svc.get("id") or "")
    spec = svc.get("spec") if isinstance(svc.get("spec"), dict) else {}
    name = str(spec.get("name") or "")
    provider = str(spec.get("provider") or "")
    tags = spec.get("tags") if isinstance(spec.get("tags"), list) else []
    if sid in known_ids or name in known_ids or any(
        f"openhouse-component:{candidate}" in tags for candidate in known_ids
    ):
        print(f"{sid}\t{name}\t{provider}")
PY
    then
      rm -rf "$tmp_dir" >/dev/null 2>&1 || true
      return 1
    fi
  else
    warn "缺少 python3，无法枚举并清理旧 aionui-web service 记录。"
    rm -rf "$tmp_dir" >/dev/null 2>&1 || true
    return 1
  fi
  rm -rf "$tmp_dir" >/dev/null 2>&1 || true
}

cleanup_aionui_service_records() {
  local token="$1"
  local records
  local sid
  local name
  local provider
  local cleaned=0

  records="$(service_manager_aionui_records "$token")" || return 1
  [ -n "$records" ] || return 0

  while IFS="$(printf '\t')" read -r sid name provider; do
    [ -n "$sid" ] || continue
    if [ "$sid" = "$service_id" ] && [ "$provider" = "proot-distro" ]; then
      continue
    fi

    if [ "$sid" = "$service_id" ]; then
      warn "发现旧 provider 的稳定 $service_id 记录（provider=$provider），删除后由 registry/apply 以 proot-distro 重建。"
    else
      warn "发现历史 AionUi service 记录（id=$sid provider=$provider），停止并删除，统一迁移到 $service_id。"
    fi
    service_manager_post "$token" "/api/v1/services/$sid/stop" >/dev/null 2>&1 || true
    service_manager_delete "$token" "/api/v1/services/$sid" || return 1
    cleaned=$((cleaned + 1))
  done <<EOF
$records
EOF

  if [ "$cleaned" -gt 0 ]; then
    log "已清理 $cleaned 个旧 $service_id service 记录。"
  fi
}

is_legacy_aionui_registry_file() {
  local file="$1"
  local kind="$2"

  [ -f "$file" ] || return 1
  python3 - "$file" "$kind" "$legacy_service_id" <<'PY'
import json
import sys

path, kind, legacy_id = sys.argv[1:]
try:
    with open(path, "r", encoding="utf-8") as f:
        document = json.load(f)
except (OSError, ValueError):
    raise SystemExit(1)
if not isinstance(document, dict):
    raise SystemExit(1)
if kind == "component":
    raise SystemExit(0 if str(document.get("id") or "") == legacy_id else 1)
service = document.get("service") if isinstance(document.get("service"), dict) else document
service_id = str(document.get("id") or service.get("name") or "")
name = str(service.get("name") or "")
tags = service.get("tags") if isinstance(service.get("tags"), list) else []
managed = (
    service_id == legacy_id
    and (
        name == legacy_id
        or f"openhouse-component:{legacy_id}" in tags
        or "openhouseai" in tags
    )
)
raise SystemExit(0 if managed else 1)
PY
}

quarantine_legacy_aionui_registry_file() {
  local file="$1"
  local kind="$2"
  local backup_root="$3"
  local label="$4"
  local target

  is_legacy_aionui_registry_file "$file" "$kind" || return 0
  mkdir -p "$backup_root" || fail "无法创建 AionUi registry 迁移备份目录：$backup_root"
  target="$backup_root/$label.json"
  if [ -e "$target" ]; then
    target="$backup_root/$label.$(date +%s).$$.json"
  fi
  mv "$file" "$target" || fail "无法隔离历史 AionUi registry 文件：$file"
  log "已隔离历史 AionUi registry 文件：$file -> $target"
}

cleanup_legacy_aionui_registry_files() {
  local ubuntu_backup="$config_dir/registry-migrations/$service_id"
  local termux_backup="$termux_config_dir/registry-migrations/$service_id"

  quarantine_legacy_aionui_registry_file \
    "$service_specs_dir/$legacy_service_id.json" service "$ubuntu_backup" ubuntu-service
  quarantine_legacy_aionui_registry_file \
    "$components_dir/$legacy_service_id.json" component "$ubuntu_backup" ubuntu-component
  quarantine_legacy_aionui_registry_file \
    "$termux_config_dir/service-manager/services.d/$legacy_service_id.json" service "$termux_backup" termux-service
  quarantine_legacy_aionui_registry_file \
    "$termux_config_dir/components.d/$legacy_service_id.json" component "$termux_backup" termux-component
}

wait_for_service_manager_after_reload() {
  local attempt

  for attempt in $(seq 1 40); do
    if service_manager_ready; then
      return 0
    fi
    sleep 1
  done
  return 1
}

reload_service_manager_file_registry() {
  local token="$1"
  local script=""

  warn "service-manager registry/apply 不可用；已写入文件 registry，将请求 Termux native 控制面修复加载。"
  for candidate in \
    "/data/data/com.termux/files/home/.smallphoneai-bootstrap/apk-assets/maintainer/repair-control-plane-termux-native.sh" \
    "/data/data/com.termux/files/home/.smallphoneai-bootstrap/maintainer/repair-control-plane-termux-native.sh"; do
    if [ -f "$candidate" ]; then
      script="$candidate"
      break
    fi
  done

  if [ -n "$script" ] && command -v openhouse-termux >/dev/null 2>&1; then
    log "通过 Ubuntu->Termux 桥修复控制中枢以加载文件 registry：$script"
    openhouse-termux exec -- bash "$script" || return 1
    wait_for_service_manager_after_reload
    return $?
  fi

  if [ -n "$script" ] && command -v oh-termux >/dev/null 2>&1; then
    log "通过 Ubuntu->Termux 桥修复控制中枢以加载文件 registry：$script"
    oh-termux exec -- bash "$script" || return 1
    wait_for_service_manager_after_reload
    return $?
  fi

  warn "无法从 Ubuntu 调用 Termux 控制面修复。请在 Termux/App 侧执行：bash ${script:-/data/data/com.termux/files/home/.smallphoneai-bootstrap/apk-assets/maintainer/repair-control-plane-termux-native.sh}"
  return 1
}

ensure_stable_service_record_for_legacy_registry() {
  local token="$1"

  if service_manager_service_resolves "$token"; then
    service_manager_put "$token" "/api/v1/services/$service_id" "$service_spec_file" \
      || return 1
    return 0
  fi

  reload_service_manager_file_registry "$token" || return 1
  token="$(resolve_service_manager_token "$service_manager_bin")"
  service_manager_auth_ready "$token" || return 1
  service_manager_service_resolves "$token"
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
  health_json="$(json_escape "http://127.0.0.1:$port_template/health")"
  core_dir_json="$(json_escape "$install_dir/bundled-aioncore/linux-arm64")"
  core_bin_json="$(json_escape "$install_dir/bundled-aioncore/linux-arm64/aioncore")"
  wrapper_json="$(json_escape "$wrapper_path")"

  write_file_from_stdin "$service_spec_file" 0644 <<EOF
{
  "name": "$service_id",
  "description": "AionUi local AI workspace",
  "provider": "proot-distro",
  "command": ["$wrapper_json"],
  "working_dir": "$install_json",
  "env": {
    "OPENHOUSE_AIONUI_INSTALL_DIR": "$install_json",
    "AIONUI_PORT": "$port_template",
    "AIONUI_DATA_DIR": "$data_json",
    "AIONUI_LOG_DIR": "$log_json",
    "AIONUI_OPEN_BROWSER": "0",
    "AIONUI_BUNDLED_AIONCORE_DIR": "$core_dir_json",
    "AIONCORE_BIN": "$core_bin_json",
    "OPENHOUSE_AIONUI_WRAPPER": "$wrapper_json"
  },
  "runtime": {
    "strategy": "proot-distro",
    "distro": "ubuntu",
    "user": "root",
    "home": "/root"
  },
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
  "ports": [
    {
      "name": "web",
      "host": "127.0.0.1",
      "preferred": $port,
      "dynamic": true,
      "pool": "local-web",
      "protocol": "tcp",
      "envVar": "AIONUI_PORT",
      "endpoint": {
        "scheme": "http",
        "path": "/"
      }
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
  local entry_url="${1:-$preferred_endpoint_url}"
  local health_json
  local service_ref_json

  health_json="$(json_escape "$entry_url")"
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
  local apply_ok=0

  api_dir="$(openhouse_mktemp_dir "openhouse-aionui-apply.XXXXXX")" || fail "无法创建 registry apply 临时目录"
  apply_payload="$api_dir/registry-apply.json"
  write_registry_apply_payload "$apply_payload"

  cleanup_aionui_service_records "$token" \
    || fail "无法清理旧 $service_id service 记录；拒绝继续以避免制造重复服务。"

  log "正在通过 service-manager 应用 AionUi registry。"
  if service_manager_post "$token" "/api/v1/registry/apply" "$apply_payload"; then
    apply_ok=1
  else
    warn "service-manager registry apply 不兼容或失败，已保留 registry 文件并进入稳定 id 兜底：$sm_url/api/v1/registry/apply"
  fi
  rm -rf "$api_dir" >/dev/null 2>&1 || true

  if [ "$apply_ok" = "0" ]; then
    sync_termux_registry_files
    ensure_stable_service_record_for_legacy_registry "$token" \
      || fail "service-manager 旧注册兜底失败：无法通过稳定 id 加载 $service_id；请从 Termux/App 修复控制中枢后重试。"
    token="$(resolve_service_manager_token "$service_manager_bin")"
    service_manager_auth_ready "$token" \
      || fail "service-manager token 在控制中枢修复后不可用。"
  fi

  log "正在注册 service-manager 服务：$service_id"
  service_manager_post "$token" "/api/v1/services/$service_id/register" \
    || fail "service-manager 服务注册失败：$service_id"

  log "正在由 service-manager 启动 AionUi：$service_ref"
  service_manager_start_request "$token" \
    || fail "service-manager 服务启动失败：$service_id"
}

wait_for_aionui_service() {
  local token="$1"
  local stable=0
  local attempt
  local endpoint_record
  local endpoint_url
  local endpoint_port

  for attempt in $(seq 1 60); do
    endpoint_record="$(service_manager_endpoint_record "$token" || true)"
    IFS="$(printf '\t')" read -r endpoint_url endpoint_port <<EOF
$endpoint_record
EOF
    if service_manager_service_running "$token" \
      && [ -n "$endpoint_record" ] \
      && [ -n "$endpoint_url" ] \
      && [ -n "$endpoint_port" ] \
      && aionui_endpoint_healthy "$endpoint_url"; then
      stable=$((stable + 1))
      if [ "$stable" -ge 2 ]; then
        printf '%s\n' "$endpoint_record"
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
  local resolved_url="${3:-$preferred_endpoint_url}"
  local resolved_port="${4:-$port}"
  mkdir -p "$config_dir"
  printf '%s\n' "$resolved_url" > "$url_file"
  {
    printf 'AIONUI_URL=%s\n' "$resolved_url"
    printf 'AIONUI_PORT=%s\n' "$resolved_port"
    printf 'AIONUI_INSTALL_DIR=%s\n' "$install_dir"
    printf 'AIONUI_DATA_DIR=%s\n' "$data_dir"
    printf 'AIONUI_LOG_DIR=%s\n' "$log_dir"
  } > "$env_file"
  printf '{"installed":true,"reachable":%s,"url":"%s","port":%s,"pid":"%s","installDir":"%s","version":"%s"}\n' \
    "$reachable" \
    "$(json_escape "$resolved_url")" \
    "$resolved_port" \
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
cleanup_legacy_aionui_registry_files
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
    log "首选端口已有 AionUi，将确认它由 service-manager 托管：$preferred_endpoint_url"
  else
    log "首选端口 $port 已被其他服务占用；将由 service-manager 从动态端口池分配可用端口。"
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

endpoint_record="$(wait_for_aionui_service "$sm_token")" || {
  write_status false ""
  fail "AionUi 未在 service-manager 托管状态下发布健康 endpoint。"
}
IFS="$(printf '\t')" read -r resolved_url resolved_port <<EOF
$endpoint_record
EOF
[ -n "$resolved_url" ] || fail "service-manager 返回了空的 AionUi endpoint URL。"
case "$resolved_port" in
  ""|*[!0-9]*) fail "service-manager 返回了无效的 AionUi endpoint 端口：$resolved_port" ;;
esac

write_component_manifest "$resolved_url"
service_manager_put "$sm_token" "/api/v1/registry/components/$service_id" "$component_file" \
  || warn "无法通过 registry API 刷新 AionUi 实际 endpoint；将保留已同步的组件文件。"
sync_termux_registry_files
write_status true "" "$resolved_url" "$resolved_port"
log "AionUi 已由 service-manager 托管并就绪：$resolved_url"
AIONUI_INSTALL
