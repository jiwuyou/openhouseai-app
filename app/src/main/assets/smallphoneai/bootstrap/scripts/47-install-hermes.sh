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

ubuntu_payload_root_for_ubuntu=""

find_termux_hermes_payload_source() {
  local termux_payload_root="$1"
  local candidate
  for candidate in \
    "$termux_payload_root/hermes.tgz" \
    "$termux_payload_root/hermes.tar" \
    "$termux_payload_root/hermes"; do
    if [ -f "$candidate" ] || [ -d "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

payload_source_stamp() {
  local source="$1"
  if [ -f "$source" ]; then
    printf 'file:%s:%s\n' "$(basename "$source")" "$(wc -c < "$source" | tr -d '[:space:]')"
  else
    printf 'dir:%s\n' "$source"
  fi
}

has_offline_wheelhouse_dir() {
  local root="$1"
  [ -d "$root/offline/wheelhouse" ] || return 1
  [ -n "$(find "$root/offline/wheelhouse" -mindepth 1 -type f -name '*.whl' -print -quit 2>/dev/null)" ]
}

stage_hermes_payload_for_ubuntu() {
  local termux_payload_root source ubuntu_rootfs guest_payload_root host_payload_root
  local staged_dir stamp source_stamp tmp_dir new_dir payload_dir flags backup_dir

  termux_payload_root="${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}"
  if ! source="$(find_termux_hermes_payload_source "$termux_payload_root")"; then
    warn "未找到 Termux 侧 Hermes payload：$termux_payload_root/hermes.tgz"
    return 1
  fi

  ubuntu_rootfs="${PREFIX:-/data/data/com.termux/files/usr}/var/lib/proot-distro/containers/ubuntu/rootfs"
  if [ ! -d "$ubuntu_rootfs/root" ]; then
    warn "Ubuntu rootfs 不存在，无法预解包 Hermes payload：$ubuntu_rootfs"
    return 1
  fi

  guest_payload_root="/root/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads"
  host_payload_root="$ubuntu_rootfs$guest_payload_root"
  staged_dir="$host_payload_root/hermes"
  stamp="$staged_dir/.smallphoneai-payload-stamp"
  source_stamp="$(payload_source_stamp "$source")"
  mkdir -p "$host_payload_root"

  if [ -f "$staged_dir/scripts/install.sh" ] \
    && [ -f "$staged_dir/scripts/check.sh" ] \
    && [ -f "$staged_dir/scripts/register-service.sh" ] \
    && has_offline_wheelhouse_dir "$staged_dir" \
    && [ -f "$stamp" ] \
    && [ "$(cat "$stamp" 2>/dev/null || true)" = "$source_stamp" ]; then
    log "Hermes payload 已缓存到 Ubuntu rootfs：$guest_payload_root/hermes"
    ubuntu_payload_root_for_ubuntu="$guest_payload_root"
    return 0
  fi

  tmp_dir="$host_payload_root/.hermes.stage.$$"
  new_dir="$host_payload_root/.hermes.new.$$"
  backup_dir="$host_payload_root/.hermes.previous.$$"
  rm -rf "$tmp_dir" "$new_dir" "$backup_dir"
  mkdir -p "$tmp_dir" "$new_dir"

  log "Hermes: 正在预解包 payload 到 Ubuntu rootfs，避免 proot 内读取大压缩包。"
  if [ -d "$source" ]; then
    if ! cp -a "$source/." "$tmp_dir/"; then
      rm -rf "$tmp_dir" "$new_dir"
      warn "Hermes payload 目录复制失败：$source"
      return 1
    fi
  else
    flags="xzf"
    case "$source" in
      *.tar) flags="xf" ;;
    esac
    if command -v timeout >/dev/null 2>&1; then
      if ! timeout "${SMALLPHONEAI_HERMES_STAGE_TIMEOUT:-300}" tar -"$flags" "$source" -C "$tmp_dir"; then
        rm -rf "$tmp_dir" "$new_dir"
        warn "Hermes payload 解包失败或超时：$source"
        return 1
      fi
    elif ! tar -"$flags" "$source" -C "$tmp_dir"; then
      rm -rf "$tmp_dir" "$new_dir"
      warn "Hermes payload 解包失败：$source"
      return 1
    fi
  fi

  if [ -f "$tmp_dir/scripts/install.sh" ]; then
    payload_dir="$tmp_dir"
  else
    payload_dir="$(find "$tmp_dir" -mindepth 2 -maxdepth 4 -path '*/scripts/install.sh' -type f -print | sed 's#/scripts/install\.sh$##' | head -n 1)"
  fi
  if [ -z "$payload_dir" ] || [ ! -d "$payload_dir" ]; then
    rm -rf "$tmp_dir" "$new_dir"
    warn "无法识别 Hermes payload 根目录：$source"
    return 1
  fi

  cp -a "$payload_dir/." "$new_dir/"
  if [ ! -f "$new_dir/scripts/install.sh" ] \
    || [ ! -f "$new_dir/scripts/check.sh" ] \
    || [ ! -f "$new_dir/scripts/register-service.sh" ]; then
    rm -rf "$tmp_dir" "$new_dir"
    warn "Hermes payload 缺少 scripts/install.sh、scripts/check.sh 或 scripts/register-service.sh：$source"
    return 1
  fi
  if ! has_offline_wheelhouse_dir "$new_dir"; then
    rm -rf "$tmp_dir" "$new_dir"
    warn "Hermes payload 缺少 offline/wheelhouse/*.whl，拒绝进入联网/apt 安装路径：$source"
    return 1
  fi

  printf '%s\n' "$source_stamp" > "$new_dir/.smallphoneai-payload-stamp"
  if [ -d "$staged_dir" ]; then
    mv "$staged_dir" "$backup_dir"
  fi
  if ! mv "$new_dir" "$staged_dir"; then
    [ -d "$backup_dir" ] && mv "$backup_dir" "$staged_dir"
    rm -rf "$tmp_dir" "$new_dir"
    warn "Hermes payload 缓存写入失败：$staged_dir"
    return 1
  fi
  rm -rf "$tmp_dir" "$backup_dir"

  ubuntu_payload_root_for_ubuntu="$guest_payload_root"
  return 0
}

if is_termux && [ "${SMALLPHONEAI_HERMES_IN_UBUNTU:-1}" = "1" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    payload_root_for_ubuntu="${SMALLPHONEAI_UBUNTU_HERMES_PAYLOAD_ROOT:-}"
    case "${SMALLPHONEAI_COMPONENT_SOURCE_MODE:-bundle}" in
      bundle|"")
        if [ -z "$payload_root_for_ubuntu" ]; then
          stage_hermes_payload_for_ubuntu || exit 1
          payload_root_for_ubuntu="$ubuntu_payload_root_for_ubuntu"
        fi
        ;;
    esac
    payload_root_for_ubuntu="${payload_root_for_ubuntu:-${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}}"
    log "正在 Ubuntu 内安装、检查并注册 Hermes。"
    SMALLPHONEAI_HERMES_IN_UBUNTU=0 \
      proot-distro login ubuntu -- env \
        SMALLPHONEAI_COMPONENT_REPO_ROOT="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-/root/smallphoneai-repos}" \
        SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="$payload_root_for_ubuntu" \
        SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="$payload_root_for_ubuntu" \
        OPENHOUSE_HERMES_OFFLINE=1 \
        SMALLPHONEAI_HERMES_OFFLINE=1 \
        HERMES_RUNTIME_ROOT="${HERMES_RUNTIME_ROOT:-/opt/openhouse/hermes}" \
        HERMES_AGENT_VENV="${HERMES_AGENT_VENV:-${HERMES_RUNTIME_ROOT:-/opt/openhouse/hermes}/venv}" \
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
export HERMES_RUNTIME_ROOT="${HERMES_RUNTIME_ROOT:-/opt/openhouse/hermes}"
export HERMES_AGENT_VENV="${HERMES_AGENT_VENV:-$HERMES_RUNTIME_ROOT/venv}"

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
    [ -f "$source/scripts/install.sh" ] \
      && [ -f "$source/scripts/check.sh" ] \
      && [ -f "$source/scripts/register-service.sh" ] \
      && has_offline_wheelhouse_dir "$source"
    return $?
  fi
  if [ -f "$source" ]; then
    tar -"$flags" "$source" >/dev/null || return 1
    tar -"$flags" "$source" | sed 's#^\./##' | grep -Eq '(^|/)scripts/install\.sh$' || return 1
    tar -"$flags" "$source" | sed 's#^\./##' | grep -Eq '(^|/)scripts/check\.sh$' || return 1
    tar -"$flags" "$source" | sed 's#^\./##' | grep -Eq '(^|/)scripts/register-service\.sh$' || return 1
    tar -"$flags" "$source" | sed 's#^\./##' | grep -Eq '(^|/)offline/wheelhouse/[^/]+\.whl$' || return 1
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
    warn "Hermes payload 缺少 scripts/install.sh、scripts/check.sh、scripts/register-service.sh 或 offline/wheelhouse/*.whl：$source"
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
  shift 2
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
  (cd "$hermes_dir" && run_logged "./$script" "$@")
}

run_hermes_install_script() {
  local install_path="$hermes_dir/scripts/install.sh"
  local args=()

  [ -f "$install_path" ] || {
    warn "Hermes 缺少必要入口：$install_path"
    return 1
  }
  if ! grep -Eq 'OPENHOUSE_HERMES_OFFLINE|SMALLPHONEAI_HERMES_OFFLINE|--offline|--no-index|offline/wheelhouse' "$install_path"; then
    warn "Hermes installer 未声明离线安装支持，拒绝运行以避免触发 apt/dpkg：$install_path"
    return 1
  fi
  if grep -q -- '--offline' "$install_path"; then
    args+=(--offline)
  fi

  log "Hermes: 强制离线安装模式，使用 offline/wheelhouse。"
  (
    export OPENHOUSE_HERMES_OFFLINE=1
    export SMALLPHONEAI_HERMES_OFFLINE=1
    export HERMES_RUNTIME_ROOT="${HERMES_RUNTIME_ROOT:-/opt/openhouse/hermes}"
    export HERMES_AGENT_VENV="${HERMES_AGENT_VENV:-$HERMES_RUNTIME_ROOT/venv}"
    export PIP_NO_INDEX=1
    run_hermes_script scripts/install.sh 1 "${args[@]}"
  )
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
run_hermes_install_script
run_hermes_script scripts/check.sh 1
run_hermes_script scripts/register-service.sh 0

log "Hermes 安装/检查/注册阶段完成。"
