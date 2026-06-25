#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
}

run_logged() {
  log "+ $*"
  "$@"
}

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

if is_termux && [ "${SMALLPHONEAI_HERMES_IN_UBUNTU:-1}" = "1" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    log "正在 Ubuntu 内安装、检查并注册 Hermes。"
    SMALLPHONEAI_HERMES_IN_UBUNTU=0 \
      proot-distro login ubuntu -- env \
        SMALLPHONEAI_COMPONENT_REPO_ROOT="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-/root/smallphoneai-repos}" \
        SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}" \
        SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}" \
        SMALLPHONEAI_COMPONENT_SOURCE_MODE="${SMALLPHONEAI_COMPONENT_SOURCE_MODE:-bundle}" \
        SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE="${SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE:-0}" \
        SMALLPHONEAI_HERMES_DIR="${SMALLPHONEAI_HERMES_DIR:-}" \
        SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-}" \
        SERVICE_MANAGER_TOKEN="${SERVICE_MANAGER_TOKEN:-}" \
        SMALLPHONE_SERVICE_MANAGER_TOKEN="${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}" \
        HERMES_WEBUI_PORT="${HERMES_WEBUI_PORT:-23084}" \
        HERMES_WEBUI_HOST="${HERMES_WEBUI_HOST:-127.0.0.1}" \
        bash -s < "$0"
    exit $?
  fi
  warn "Ubuntu 尚不可用，将在当前 Termux 环境尝试 Hermes 安装。"
fi

export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:$PATH"

repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$HOME/smallphoneai-repos}"
payload_root="${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-${SMALLPHONEAI_PAYLOAD_ROOT:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}}"
component_source_mode="${SMALLPHONEAI_COMPONENT_SOURCE_MODE:-bundle}"
allow_git_update="${SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE:-0}"
hermes_dir="${SMALLPHONEAI_HERMES_DIR:-$repo_root/hermes}"
service_manager_dir="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-$repo_root/service-manager}"
service_manager_bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
service_manager_url="${SERVICE_MANAGER_URL:-http://$service_manager_bind}"

find_payload_source() {
  local payload_name="$1"
  local candidate
  for candidate in \
    "$payload_root/$payload_name.tgz" \
    "$payload_root/$payload_name.tar" \
    "$payload_root/$payload_name"; do
    if [ -f "$candidate" ] || [ -d "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

validate_payload_source() {
  local source="$1"
  local flags="tzf"
  case "$source" in
    *.tar) flags="tf" ;;
  esac

  if [ -d "$source" ]; then
    [ -f "$source/scripts/install.sh" ] && [ -f "$source/scripts/check.sh" ] && [ -f "$source/scripts/register-service.sh" ]
    return $?
  fi
  if [ -f "$source" ]; then
    tar -"$flags" "$source" >/dev/null
    tar -"$flags" "$source" | sed 's#^\./##' | grep -Eq '(^|/)scripts/install\.sh$'
    tar -"$flags" "$source" | sed 's#^\./##' | grep -Eq '(^|/)scripts/check\.sh$'
    tar -"$flags" "$source" | sed 's#^\./##' | grep -Eq '(^|/)scripts/register-service\.sh$'
    return 0
  fi
  return 1
}

copy_payload_dir() {
  local source="$1"
  local dir="$2"
  mkdir -p "$dir"
  cp -a "$source/." "$dir/"
}

extract_payload_archive() {
  local source="$1"
  local dir="$2"
  local flags="xzf"
  local work_dir
  local payload_dir
  case "$source" in
    *.tar) flags="xf" ;;
  esac
  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-hermes.XXXXXX")"
  tar -"$flags" "$source" -C "$work_dir"
  if [ -f "$work_dir/scripts/install.sh" ]; then
    payload_dir="$work_dir"
  else
    payload_dir="$(find "$work_dir" -mindepth 2 -maxdepth 3 -path '*/scripts/install.sh' -type f -print | sed 's#/scripts/install\.sh$##' | head -n 1)"
  fi
  if [ -z "$payload_dir" ] || [ ! -d "$payload_dir" ]; then
    warn "无法识别 Hermes payload 根目录：$source"
    return 1
  fi
  mkdir -p "$dir"
  cp -a "$payload_dir/." "$dir/"
  rm -rf "$work_dir"
}

prepare_from_bundle() {
  local source
  if ! source="$(find_payload_source hermes)"; then
    warn "未找到 APK 内置 Hermes payload：$payload_root/hermes.tgz"
    return 1
  fi
  validate_payload_source "$source" || {
    warn "Hermes payload 缺少 scripts/install.sh、scripts/check.sh 或 scripts/register-service.sh：$source"
    return 1
  }
  log "Hermes: 从 APK-bundled payload 安装到 $hermes_dir"
  if [ -d "$source" ]; then
    copy_payload_dir "$source" "$hermes_dir"
  else
    extract_payload_archive "$source" "$hermes_dir"
  fi
}

prepare_from_git_update() {
  local url="${SMALLPHONEAI_HERMES_GIT_URL:-https://github.com/jiwuyou/hermes-webui.git}"
  if [ -d "$hermes_dir" ]; then
    return 0
  fi
  if [ "$allow_git_update" != "1" ]; then
    warn "未找到 Hermes 安装目录 $hermes_dir；默认首装只允许 APK-bundled payload。"
    return 1
  fi
  command -v git >/dev/null 2>&1 || {
    warn "缺少 git，无法可选拉取 Hermes。"
    return 1
  }
  mkdir -p "$(dirname "$hermes_dir")"
  log "Hermes: 可选更新路径正在拉取 $url -> $hermes_dir"
  git clone --depth 1 "$url" "$hermes_dir"
}

find_service_manager_binary() {
  if command -v service-manager >/dev/null 2>&1; then
    command -v service-manager
    return 0
  fi
  if [ -x "$service_manager_dir/service-manager" ]; then
    printf '%s\n' "$service_manager_dir/service-manager"
    return 0
  fi
  if [ -x "$service_manager_dir/target/release/service-manager" ]; then
    printf '%s\n' "$service_manager_dir/target/release/service-manager"
    return 0
  fi
  return 1
}

service_manager_health_ready() {
  command -v curl >/dev/null 2>&1 || return 1
  curl -fsS --max-time 2 "$service_manager_url/api/v1/health" >/dev/null 2>&1
}

start_service_manager_for_registration() {
  local sm_bin="$1"
  mkdir -p "$HOME/.smallphoneai/logs"
  log "正在启动 service-manager 以注册 Hermes：$service_manager_bind"
  nohup "$sm_bin" serve --bind "$service_manager_bind" > "$HOME/.smallphoneai/logs/service-manager.log" 2>&1 < /dev/null &
  for _ in $(seq 1 30); do
    if service_manager_health_ready; then
      return 0
    fi
    sleep 1
  done
  return 1
}

ensure_service_manager_context() {
  local sm_bin
  local token
  sm_bin="$(find_service_manager_binary || true)"
  [ -n "$sm_bin" ] || {
    warn "未找到 service-manager；Hermes 会写入组件 manifest，但跳过服务注册。"
    return 0
  }
  export PATH="$(dirname "$sm_bin"):$PATH"
  if ! service_manager_health_ready; then
    start_service_manager_for_registration "$sm_bin" || {
      warn "service-manager 未能启动；Hermes 会跳过服务注册。"
      return 0
    }
  fi
  token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  if [ -z "$token" ]; then
    token="$("$sm_bin" token show 2>/dev/null | tr -d '\r\n' || true)"
  fi
  if [ -n "$token" ]; then
    export SERVICE_MANAGER_URL="$service_manager_url"
    export SERVICE_MANAGER_TOKEN="$token"
    export SMALLPHONE_SERVICE_MANAGER_TOKEN="${SMALLPHONE_SERVICE_MANAGER_TOKEN:-$token}"
  fi
}

run_hermes_script() {
  local script="$1"
  local required="$2"
  local path="$hermes_dir/$script"
  if [ ! -f "$path" ]; then
    if [ "$required" = "1" ]; then
      warn "Hermes 缺少必要入口：$path"
      return 1
    fi
    warn "Hermes 可选入口不存在，跳过：$path"
    return 0
  fi
  chmod +x "$path"
  log "Hermes: 执行 $script"
  (cd "$hermes_dir" && run_logged "./$script")
}

case "$component_source_mode" in
  bundle|"")
    prepare_from_bundle
    ;;
  git-update)
    prepare_from_git_update
    ;;
  *)
    warn "未知 SMALLPHONEAI_COMPONENT_SOURCE_MODE=$component_source_mode"
    exit 1
    ;;
esac

ensure_service_manager_context
run_hermes_script scripts/install.sh 1
run_hermes_script scripts/check.sh 1
run_hermes_script scripts/register-service.sh 0

log "Hermes 安装/检查/注册阶段完成。"
