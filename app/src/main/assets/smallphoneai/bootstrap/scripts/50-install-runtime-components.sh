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

detect_smallphoneai_runtime() {
  if is_current_ubuntu; then
    printf 'ubuntu'
    return 0
  fi

  if [ -x "${PREFIX:-/data/data/com.termux/files/usr}/bin/smallphoneai-env-probe" ]; then
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/smallphoneai-env-probe" 2>/dev/null \
      | awk -F= '$1=="SMALLPHONEAI_RUNTIME"{print $2; found=1} END{if(!found) exit 1}' \
      && return 0
  fi

  if is_termux; then
    printf 'termux'
    return 0
  fi

  printf 'unknown'
}

if is_termux && [ "${SMALLPHONEAI_RUNTIME_COMPONENTS_IN_UBUNTU:-1}" = "1" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    log "正在 Ubuntu 内安装、检查并注册 SmallPhone 运行组件。"
    SMALLPHONEAI_RUNTIME_COMPONENTS_IN_UBUNTU=0 \
      proot-distro login ubuntu -- env \
        SMALLPHONEAI_COMPONENT_REPO_ROOT="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-/root/smallphoneai-repos}" \
        SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}" \
        SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}" \
        SMALLPHONEAI_COMPONENT_SOURCE_MODE="${SMALLPHONEAI_COMPONENT_SOURCE_MODE:-bundle}" \
        SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE="${SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE:-0}" \
        SMALLPHONEAI_COMPONENTS_AUTO_CLONE="${SMALLPHONEAI_COMPONENTS_AUTO_CLONE:-0}" \
        SMALLPHONEAI_COMPONENTS_STRICT="${SMALLPHONEAI_COMPONENTS_STRICT:-1}" \
        SMALLPHONEAI_COMPONENT_TARGETS="${SMALLPHONEAI_COMPONENT_TARGETS:-}" \
        SMALLPHONEAI_SERVICE_MANAGER_DIR="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-}" \
        SMALLPHONEAI_CC_CONNECT_DIR="${SMALLPHONEAI_CC_CONNECT_DIR:-}" \
        SMALLPHONEAI_SMALLPHONE_DIR="${SMALLPHONEAI_SMALLPHONE_DIR:-}" \
        SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-}" \
        SERVICE_MANAGER_TOKEN="${SERVICE_MANAGER_TOKEN:-}" \
        SMALLPHONE_SERVICE_MANAGER_TOKEN="${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}" \
        bash -s < "$0"
    exit $?
  fi
  warn "Ubuntu 尚不可用，将在当前 Termux 环境尝试运行组件入口。"
fi

export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"

repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$HOME/smallphoneai-repos}"
payload_root="${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-${SMALLPHONEAI_PAYLOAD_ROOT:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}}"
component_source_mode="${SMALLPHONEAI_COMPONENT_SOURCE_MODE:-bundle}"
allow_git_update="${SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE:-${SMALLPHONEAI_COMPONENTS_AUTO_CLONE:-0}}"
strict="${SMALLPHONEAI_COMPONENTS_STRICT:-1}"
component_targets="${SMALLPHONEAI_COMPONENT_TARGETS:-}"
failures=0

normalize_target() {
  case "$1" in
    openhouse-connect)
      printf 'cc-connect'
      ;;
    *)
      printf '%s' "$1"
      ;;
  esac
}

should_run_component() {
  local target="$1"
  local wanted rest item
  [ -n "$component_targets" ] || return 0

  rest="$component_targets"
  while [ -n "$rest" ]; do
    case "$rest" in
      *,*)
        item="${rest%%,*}"
        rest="${rest#*,}"
        ;;
      *)
        item="$rest"
        rest=""
        ;;
    esac
    wanted="$(normalize_target "$item")"
    [ "$wanted" = "$target" ] && return 0
  done

  return 1
}

validate_component_targets() {
  local target rest item
  rest="$component_targets"
  [ -n "$rest" ] || return 0

  while [ -n "$rest" ]; do
    case "$rest" in
      *,*)
        item="${rest%%,*}"
        rest="${rest#*,}"
        ;;
      *)
        item="$rest"
        rest=""
        ;;
    esac

    [ -n "$item" ] || continue
    target="$(normalize_target "$item")"
    case "$target" in
      service-manager|cc-connect|smallphone)
        ;;
      *)
        warn "未知组件目标：$item"
        failures=$((failures + 1))
        ;;
    esac
  done
}

default_path() {
  local repo_name="$1"
  printf '%s/%s\n' "$repo_root" "$repo_name"
}

payload_archive_contains() {
  local archive="$1"
  local pattern="$2"
  local list_file
  local status
  local tar_list_flags="tzf"

  case "$archive" in
    *.tar)
      tar_list_flags="tf"
      ;;
  esac
  list_file="$(mktemp "${TMPDIR:-/tmp}/smallphoneai-payload-list.XXXXXX")"
  tar -"$tar_list_flags" "$archive" | sed 's#^\./##' > "$list_file"
  grep -Eq "$pattern" "$list_file"
  status=$?
  rm -f "$list_file"
  return "$status"
}

validate_payload_source() {
  local name="$1"
  local source="$2"
  local tar_list_flags="tzf"

  case "$source" in
    *.tar)
      tar_list_flags="tf"
      ;;
  esac

  if [ -d "$source" ]; then
    if [ ! -f "$source/scripts/install.sh" ] || [ ! -f "$source/scripts/check.sh" ]; then
      warn "$name: APK payload directory is missing scripts/install.sh or scripts/check.sh: $source"
      return 1
    fi
    return 0
  fi

  if [ -f "$source" ]; then
    if ! tar -"$tar_list_flags" "$source" >/dev/null 2>&1; then
      warn "$name: APK payload archive is not a readable tar/tar.gz: $source"
      return 1
    fi
    if ! payload_archive_contains "$source" '(^|/)scripts/install\.sh$' \
      || ! payload_archive_contains "$source" '(^|/)scripts/check\.sh$'; then
      warn "$name: APK payload archive must contain scripts/install.sh and scripts/check.sh: $source"
      return 1
    fi
    return 0
  fi

  warn "$name: missing APK-bundled payload: $source"
  return 1
}

find_payload_source() {
  local payload_name="$1"
  local candidate

  for candidate in \
    "$payload_root/$payload_name.tar.gz" \
    "$payload_root/$payload_name.tgz" \
    "$payload_root/$payload_name.tar" \
    "$payload_root/$payload_name"; do
    if [ -f "$candidate" ] || [ -d "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  printf '%s\n' "$payload_root/$payload_name.tar.gz"
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
  local work_dir
  local payload_dir
  local tar_extract_flags="xzf"

  case "$source" in
    *.tar)
      tar_extract_flags="xf"
      ;;
  esac

  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-payload.XXXXXX")"
  tar -"$tar_extract_flags" "$source" -C "$work_dir"

  if [ -f "$work_dir/scripts/install.sh" ]; then
    payload_dir="$work_dir"
  else
    payload_dir="$(find "$work_dir" -mindepth 3 -maxdepth 3 -path '*/scripts/install.sh' -type f -print | sed 's#/scripts/install\.sh$##' | head -n 1)"
  fi

  if [ -z "$payload_dir" ] || [ ! -d "$payload_dir" ]; then
    warn "无法识别 APK payload 解压后的组件根目录：$source"
    rm -rf "$work_dir"
    return 1
  fi

  mkdir -p "$dir"
  cp -a "$payload_dir/." "$dir/"
  rm -rf "$work_dir"
}

install_payload_if_needed() {
  local name="$1"
  local payload_name="$2"
  local dir="$3"
  local source

  source="$(find_payload_source "$payload_name" || true)"
  if ! validate_payload_source "$name" "$source"; then
    return 1
  fi

  if [ -f "$dir/scripts/install.sh" ] && [ -f "$dir/scripts/check.sh" ]; then
    log "$name: 已存在安装目录，已验证 APK payload 可用：$dir"
    return 0
  fi

  log "$name: 从 APK-bundled payload 安装到 $dir"
  if [ -d "$source" ]; then
    copy_payload_dir "$source" "$dir"
  else
    extract_payload_archive "$source" "$dir"
  fi
}

prepare_component_from_git_update() {
  local name="$1"
  local dir="$2"
  local url="$3"

  if [ -d "$dir/.git" ] || [ -d "$dir" ]; then
    if [ "$allow_git_update" = "1" ] && [ -d "$dir/.git" ]; then
      if command -v git >/dev/null 2>&1; then
        log "$name: 可选更新路径已开启，尝试 git pull --ff-only。"
        (cd "$dir" && git pull --ff-only) || warn "$name: git 更新失败，继续使用本地目录。"
      else
        warn "$name: 缺少 git，跳过可选更新。"
      fi
    fi
    return 0
  fi

  if [ "$allow_git_update" != "1" ]; then
    warn "$name: 未找到安装目录 $dir；默认首装只允许 APK-bundled payload。"
    return 1
  fi

  if [ -z "$url" ]; then
    warn "$name: 未配置 git URL，无法自动拉取。"
    return 1
  fi

  if ! command -v git >/dev/null 2>&1; then
    warn "$name: 缺少 git，无法自动拉取 $url。"
    return 1
  fi

  mkdir -p "$(dirname "$dir")"
  log "$name: 可选更新路径正在拉取仓库 $url -> $dir"
  git clone --depth 1 "$url" "$dir"
}

prepare_component() {
  local name="$1"
  local dir="$2"
  local url="$3"
  local payload_name="$4"

  case "$component_source_mode" in
    bundle|"")
      install_payload_if_needed "$name" "$payload_name" "$dir"
      ;;
    git-update)
      prepare_component_from_git_update "$name" "$dir" "$url"
      ;;
    *)
      warn "$name: 未知 SMALLPHONEAI_COMPONENT_SOURCE_MODE=$component_source_mode"
      return 1
      ;;
  esac
}

run_repo_script() {
  local name="$1"
  local dir="$2"
  local script="$3"
  local required="$4"
  local path="$dir/$script"

  if [ ! -f "$path" ]; then
    if [ "$required" = "1" ]; then
      warn "$name: 缺少必要入口 $path"
      failures=$((failures + 1))
    else
      warn "$name: 可选入口不存在，跳过 $path"
    fi
    return 0
  fi

  chmod +x "$path"
  log "$name: 执行 $script"
  if ! (cd "$dir" && run_logged "./$script"); then
    if [ "$required" = "1" ]; then
      warn "$name: $script 执行失败"
      failures=$((failures + 1))
    else
      warn "$name: 可选入口 $script 执行失败，继续。"
    fi
  fi
}

run_component() {
  local name="$1"
  local dir="$2"
  local url="$3"
  local required="$4"
  local payload_name="$5"

  if ! prepare_component "$name" "$dir" "$url" "$payload_name"; then
    if [ "$required" = "1" ]; then
      failures=$((failures + 1))
    fi
    return 0
  fi

  run_repo_script "$name" "$dir" "scripts/install.sh" "$required"
  run_repo_script "$name" "$dir" "scripts/check.sh" "$required"
  run_repo_script "$name" "$dir" "scripts/register-service.sh" "0"
}

service_manager_dir="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-$(default_path service-manager)}"
cc_connect_dir="${SMALLPHONEAI_CC_CONNECT_DIR:-$(default_path openhouse-connect)}"
smallphone_dir="${SMALLPHONEAI_SMALLPHONE_DIR:-$(default_path smallphone-active)}"

log "SmallPhoneAI 运行组件入口由各子仓库维护。"
log "当前运行环境：$(detect_smallphoneai_runtime)"
log "组件仓库根目录：$repo_root"
log "组件来源模式：$component_source_mode"
if [ "$component_source_mode" = "bundle" ]; then
  log "APK payload 根目录：$payload_root"
fi
if [ -n "$component_targets" ]; then
  log "本次仅处理指定组件：$component_targets"
else
  log "本次处理默认组件：service-manager、cc-connect/openhouse-connect、SmallPhone。"
fi

validate_component_targets

if should_run_component "service-manager"; then
  run_component "service-manager" "$service_manager_dir" "${SMALLPHONEAI_SERVICE_MANAGER_GIT_URL:-https://github.com/jiwuyou/service-manager.git}" "1" "service-manager"
fi
if should_run_component "cc-connect"; then
  run_component "cc-connect/openhouse-connect" "$cc_connect_dir" "${SMALLPHONEAI_CC_CONNECT_GIT_URL:-https://github.com/jiwuyou/openhouse-connect.git}" "1" "openhouse-connect"
fi
if should_run_component "smallphone"; then
  run_component "SmallPhone" "$smallphone_dir" "${SMALLPHONEAI_SMALLPHONE_GIT_URL:-https://github.com/jiwuyou/wuxian-smallphone.git}" "1" "smallphone"
fi

if [ "$failures" -ne 0 ]; then
  if [ "$strict" = "1" ]; then
    warn "SmallPhoneAI 运行组件存在 $failures 个失败项。"
    exit 1
  fi
  warn "SmallPhoneAI 运行组件存在 $failures 个失败项；SMALLPHONEAI_COMPONENTS_STRICT=0，继续。"
fi

log "SmallPhoneAI 运行组件安装/检查/注册阶段完成。"
