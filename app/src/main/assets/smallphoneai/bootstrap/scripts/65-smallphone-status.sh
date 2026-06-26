#!/usr/bin/env bash
set -euo pipefail

mode="${1:-status}"

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

detect_runtime() {
  if is_current_ubuntu; then
    printf 'ubuntu'
  elif is_termux; then
    printf 'termux'
  else
    printf 'unknown'
  fi
}

json_escape() {
  printf '%s' "$1" | sed \
    -e 's/\\/\\\\/g' \
    -e 's/"/\\"/g' \
    -e ':a;N;$!ba;s/\n/\\n/g' \
    -e 's/\r/\\r/g' \
    -e 's/\t/\\t/g'
}

json_string() {
  printf '"%s"' "$(json_escape "$1")"
}

bool() {
  if [ "$1" = "1" ]; then
    printf 'true'
  else
    printf 'false'
  fi
}

is_truthy() {
  case "${1:-}" in
    1|true|TRUE|True|yes|YES|Yes|on|ON|On)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

have() {
  command -v "$1" >/dev/null 2>&1
}

probe_url() {
  local url="$1"
  if have curl && curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
    printf '1'
  else
    printf '0'
  fi
}

probe_tcp() {
  local host="${1:-}"
  local port="${2:-}"
  case "$host" in
    ""|*[!A-Za-z0-9_.-]*)
      printf '0'
      return
      ;;
  esac
  case "$port" in
    ""|*[!0-9]*)
      printf '0'
      return
      ;;
  esac
  if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ] || ! command -v timeout >/dev/null 2>&1; then
    printf '0'
    return
  fi
  if timeout 2 bash -c ': >/dev/tcp/$1/$2' _ "$host" "$port" >/dev/null 2>&1; then
    printf '1'
  else
    printf '0'
  fi
}

if [ "$mode" = "hooks" ]; then
  cat <<'EOF'
{"schema":1,"product":"SmallPhoneAI","hooks":[{"id":"install","command":["bash","bootstrap.sh","install"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}},{"id":"full","command":["bash","bootstrap.sh","full"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}},{"id":"check","command":["bash","bootstrap.sh","check"],"output":"json","idempotent":true},{"id":"status","command":["bash","bootstrap.sh","status"],"output":"json","idempotent":true},{"id":"hooks","command":["bash","bootstrap.sh","hooks"],"output":"json","idempotent":true},{"id":"start","command":["bash","bootstrap.sh","start"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}},{"id":"repair","command":["bash","bootstrap.sh","repair"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}},{"id":"components","command":["bash","bootstrap.sh","components"],"idempotent":true},{"id":"sync-core-stack","command":["bash","bootstrap.sh","sync-core-stack"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}},{"id":"post-apk-update","command":["bash","bootstrap.sh","post-apk-update"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}}]}
EOF
  exit 0
fi

if is_termux && [ "${SMALLPHONEAI_STATUS_IN_UBUNTU:-1}" = "1" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    SMALLPHONEAI_STATUS_IN_UBUNTU=0 \
      proot-distro login ubuntu -- env \
        SMALLPHONEAI_COMPONENT_REPO_ROOT="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-/root/smallphoneai-repos}" \
        SMALLPHONEAI_ALLOW_DEV_COMPONENT_PATHS="${SMALLPHONEAI_ALLOW_DEV_COMPONENT_PATHS:-}" \
        SMALLPHONEAI_SERVICE_MANAGER_DIR="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-}" \
        SMALLPHONEAI_CC_CONNECT_DIR="${SMALLPHONEAI_CC_CONNECT_DIR:-}" \
        SMALLPHONEAI_HERMES_DIR="${SMALLPHONEAI_HERMES_DIR:-}" \
        SMALLPHONEAI_CC_CONNECT_DISABLED="${SMALLPHONEAI_CC_CONNECT_DISABLED:-}" \
        SMALLPHONEAI_DISABLE_CC_CONNECT="${SMALLPHONEAI_DISABLE_CC_CONNECT:-}" \
        SMALLPHONEAI_CC_CONNECT_HOST="${SMALLPHONEAI_CC_CONNECT_HOST:-}" \
        SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT="${SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT:-}" \
        SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT="${SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT:-}" \
        SMALLPHONEAI_SMALLPHONE_DIR="${SMALLPHONEAI_SMALLPHONE_DIR:-}" \
        SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-}" \
        bash -s status < "$0"
    exit $?
  fi
fi

repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-/root/smallphoneai-repos}"
allow_dev_component_paths() {
  case "${SMALLPHONEAI_ALLOW_DEV_COMPONENT_PATHS:-0}" in
    1|true|TRUE|True|yes|YES|Yes|on|ON|On)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

normalize_component_path() {
  local path="${1:-}"
  while [ "${path%/}" != "$path" ] && [ "$path" != "/" ]; do
    path="${path%/}"
  done
  printf '%s\n' "$path"
}

is_known_dev_component_path() {
  local path
  path="$(normalize_component_path "$1")"
  case "$path" in
    /root/projects/service-manager|/root/openhouse-connect-fresh|/root/cc-connect-fresh|/root/projects/smallphone/smallphone-active)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

default_path() {
  local repo_name="$1"
  shift
  local product_path="$repo_root/$repo_name"
  if allow_dev_component_paths; then
    local dev_path
    for dev_path in "$@"; do
      if [ -d "$dev_path" ]; then
        printf '%s\n' "$dev_path"
        return
      fi
    done
  fi
  printf '%s\n' "$product_path"
}

component_dir_from_env() {
  local env_value="$1"
  local repo_name="$2"
  shift 2
  local product_path="$repo_root/$repo_name"
  if [ -n "$env_value" ]; then
    if is_known_dev_component_path "$env_value" && ! allow_dev_component_paths; then
      printf '%s\n' "$product_path"
    else
      printf '%s\n' "$env_value"
    fi
    return
  fi
  default_path "$repo_name" "$@"
}

component_object() {
  local id="$1"
  local label="$2"
  local dir="$3"
  local enabled="${4:-1}"
  local present=0 install=0 check=0 register=0
  [ -d "$dir" ] && present=1
  [ -f "$dir/scripts/install.sh" ] && install=1
  [ -f "$dir/scripts/check.sh" ] && check=1
  [ -f "$dir/scripts/register-service.sh" ] && register=1
  printf '{"id":'
  json_string "$id"
  printf ',"label":'
  json_string "$label"
  printf ',"path":'
  json_string "$dir"
  printf ',"enabled":%s,"repoPresent":%s,"installScript":%s,"checkScript":%s,"registerServiceScript":%s}' \
    "$(bool "$enabled")" \
    "$(bool "$present")" "$(bool "$install")" "$(bool "$check")" "$(bool "$register")"
}

port_object() {
  local id="$1"
  local url="$2"
  local reachable="$3"
  local enabled="${4:-1}"
  printf '{"id":'
  json_string "$id"
  printf ',"url":'
  json_string "$url"
  printf ',"enabled":%s,"reachable":%s}' "$(bool "$enabled")" "$(bool "$reachable")"
}

control_test_object() {
  local id="$1"
  local label="$2"
  local service_id="$3"
  local url="$4"
  local reachable="$5"
  printf '{"id":'
  json_string "$id"
  printf ',"label":'
  json_string "$label"
  printf ',"serviceId":'
  json_string "$service_id"
  printf ',"url":'
  json_string "$url"
  printf ',"required":false,"reachable":%s}' "$(bool "$reachable")"
}

readiness_object() {
  local id="$1"
  local label="$2"
  local url="$3"
  local reachable="$4"
  local required="$5"
  local disabled="$6"
  local satisfied=0

  if [ "$disabled" = "1" ] || [ "$reachable" = "1" ]; then
    satisfied=1
  fi

  printf '{"id":'
  json_string "$id"
  printf ',"label":'
  json_string "$label"
  printf ',"url":'
  json_string "$url"
  printf ',"required":%s,"disabled":%s,"reachable":%s,"satisfied":%s}' \
    "$(bool "$required")" "$(bool "$disabled")" "$(bool "$reachable")" "$(bool "$satisfied")"
}

service_manager_dir="$(component_dir_from_env "${SMALLPHONEAI_SERVICE_MANAGER_DIR:-}" service-manager /root/projects/service-manager)"
cc_connect_dir="$(component_dir_from_env "${SMALLPHONEAI_CC_CONNECT_DIR:-}" openhouse-connect /root/openhouse-connect-fresh /root/cc-connect-fresh)"
smallphone_dir="$(component_dir_from_env "${SMALLPHONEAI_SMALLPHONE_DIR:-}" smallphone-active /root/projects/smallphone/smallphone-active)"
hermes_dir="${SMALLPHONEAI_HERMES_DIR:-$repo_root/hermes}"

sm_url="${SERVICE_MANAGER_URL:-http://127.0.0.1:20087}"
cc_host="${SMALLPHONEAI_CC_CONNECT_HOST:-127.0.0.1}"
cc_bridge_port="${SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT:-21010}"
cc_management_port="${SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT:-21020}"
cc_url="bridge=${cc_host}:${cc_bridge_port}, management=${cc_host}:${cc_management_port}"
smallphone_core_url="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-http://127.0.0.1:22000/}"
smallphone_url="${SMALLPHONEAI_SMALLPHONE_URL:-http://127.0.0.1:22082/}"
hermes_url="${HERMES_WEBUI_URL:-http://127.0.0.1:23084/}"
likegirl_url="${SMALLPHONEAI_LIKEGIRL_URL:-http://127.0.0.1:23003/}"
likegirl_clone_url="${SMALLPHONEAI_LIKEGIRL_CLONE_URL:-http://127.0.0.1:23008/}"
cc_connect_disabled=0
if is_truthy "${SMALLPHONEAI_CC_CONNECT_DISABLED:-${SMALLPHONEAI_DISABLE_CC_CONNECT:-0}}"; then
  cc_connect_disabled=1
fi
cc_connect_enabled=1
cc_connect_required=1
if [ "$cc_connect_disabled" = "1" ]; then
  cc_connect_enabled=0
  cc_connect_required=0
fi

sm_reachable="$(probe_url "$sm_url/api/v1/health")"
if [ "$sm_reachable" != "1" ]; then
  sm_reachable="$(probe_url "$sm_url/")"
fi
cc_bridge_reachable="$(probe_tcp "$cc_host" "$cc_bridge_port")"
cc_management_reachable="$(probe_tcp "$cc_host" "$cc_management_port")"
cc_reachable=0
if [ "$cc_bridge_reachable" = "1" ] && [ "$cc_management_reachable" = "1" ]; then
  cc_reachable=1
fi
smallphone_core_reachable="$(probe_url "$smallphone_core_url")"
smallphone_reachable="$(probe_url "$smallphone_url")"
hermes_reachable="$(probe_url "$hermes_url")"
likegirl_reachable="$(probe_url "$likegirl_url")"
likegirl_clone_reachable="$(probe_url "$likegirl_clone_url")"
cc_connect_satisfied="$cc_reachable"
if [ "$cc_connect_disabled" = "1" ]; then
  cc_connect_satisfied=1
fi
ready=0
if [ "$sm_reachable" = "1" ] \
  && [ "$smallphone_reachable" = "1" ] \
  && [ "$smallphone_core_reachable" = "1" ] \
  && [ "$cc_connect_satisfied" = "1" ]; then
  ready=1
fi

state="missing"
if [ "$ready" = "1" ]; then
  state="ready"
elif [ "$sm_reachable" = "1" ] || [ -d "$service_manager_dir" ] || [ -d "$cc_connect_dir" ] || [ -d "$smallphone_dir" ]; then
  state="partial"
fi

printf '{"schema":1,"product":"SmallPhoneAI","state":'
json_string "$state"
printf ',"runtime":'
json_string "$(detect_runtime)"
printf ',"ready":%s' "$(bool "$ready")"
printf ',"readiness":{"ready":%s,"requirements":[' "$(bool "$ready")"
readiness_object "service-manager" "service-manager API" "$sm_url" "$sm_reachable" "1" "0"
printf ','
readiness_object "smallphone" "SmallPhone frontend" "$smallphone_url" "$smallphone_reachable" "1" "0"
printf ','
readiness_object "smallphone-core" "SmallPhone core API" "$smallphone_core_url" "$smallphone_core_reachable" "1" "0"
printf ','
readiness_object "cc-connect-bridge" "cc-connect/openhouse-connect bridge and management" "$cc_url" "$cc_reachable" "$cc_connect_required" "$cc_connect_disabled"
printf ']}'
printf ',"components":['
component_object "service-manager" "service-manager" "$service_manager_dir"
printf ','
component_object "cc-connect" "cc-connect/openhouse-connect" "$cc_connect_dir" "$cc_connect_enabled"
printf ','
component_object "smallphone" "SmallPhone" "$smallphone_dir"
printf ','
component_object "hermes-webui" "Hermes WebUI" "$hermes_dir"
printf '],"ports":['
port_object "service-manager" "$sm_url" "$sm_reachable"
printf ','
port_object "cc-connect-bridge" "tcp://${cc_host}:${cc_bridge_port}" "$cc_bridge_reachable" "$cc_connect_enabled"
printf ','
port_object "cc-connect-management" "tcp://${cc_host}:${cc_management_port}" "$cc_management_reachable" "$cc_connect_enabled"
printf ','
port_object "smallphone-core" "$smallphone_core_url" "$smallphone_core_reachable"
printf ','
port_object "smallphone" "$smallphone_url" "$smallphone_reachable"
printf ','
port_object "hermes-webui" "$hermes_url" "$hermes_reachable"
printf ','
port_object "smallphone-likegirl" "$likegirl_url" "$likegirl_reachable"
printf ','
port_object "smallphone-likegirl-clone" "$likegirl_clone_url" "$likegirl_clone_reachable"
printf '],"controlTests":['
control_test_object "smallphone-likegirl" "smallphone-likegirl control test" "smallphone-standalone-like-girl" "$likegirl_url" "$likegirl_reachable"
printf ','
control_test_object "smallphone-likegirl-clone" "smallphone-likegirl clone control test" "smallphone-standalone-like-girl-clone" "$likegirl_clone_url" "$likegirl_clone_reachable"
printf '],"actions":{"install":["bash","bootstrap.sh","install"],"check":["bash","bootstrap.sh","check"],"status":["bash","bootstrap.sh","status"],"start":["bash","bootstrap.sh","start"],"repair":["bash","bootstrap.sh","repair"]}}\n'
