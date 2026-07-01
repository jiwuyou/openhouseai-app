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
        SMALLPHONEAI_FORCE_PAYLOAD_REFRESH="${SMALLPHONEAI_FORCE_PAYLOAD_REFRESH:-0}" \
        SMALLPHONEAI_TERMUX_HOME="${SMALLPHONEAI_TERMUX_HOME:-$HOME}" \
        SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG="${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
        SMALLPHONEAI_SERVICE_MANAGER_BIND="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}" \
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
force_payload_refresh="${SMALLPHONEAI_FORCE_PAYLOAD_REFRESH:-0}"
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
      service-manager|cc-connect|smallphone|pi-agent|pi-web)
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

payload_archive_contains_executable() {
  local archive="$1"
  local pattern="$2"
  local tar_list_flags="tzvf"

  case "$archive" in
    *.tar)
      tar_list_flags="tvf"
      ;;
  esac

  tar -"$tar_list_flags" "$archive" | awk -v pattern="$pattern" '
    {
      mode = $1
      name = $0
      sub(/^[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+[^[:space:]]+[[:space:]]+/, "", name)
      sub(/^\.\//, "", name)
      if (substr(mode, 1, 1) == "-" && mode ~ /x/ && name ~ pattern) {
        found = 1
      }
    }
    END { exit(found ? 0 : 1) }
  '
}

payload_dir_contains_executable() {
  local source="$1"
  local payload_name="$2"

  case "$payload_name" in
    service-manager)
      [ -x "$source/service-manager" ] || [ -x "$source/target/release/service-manager" ]
      ;;
    openhouse-connect)
      [ -x "$source/cc-connect" ]
      ;;
    *)
      return 1
      ;;
  esac
}

required_payload_executable() {
  case "$1" in
    service-manager)
      printf 'service-manager'
      ;;
    openhouse-connect)
      printf 'cc-connect'
      ;;
    *)
      return 1
      ;;
  esac
}

required_payload_executable_pattern() {
  case "$1" in
    service-manager)
      printf '^(service-manager|target/release/service-manager)$'
      ;;
    openhouse-connect)
      printf '^cc-connect$'
      ;;
    *)
      return 1
      ;;
  esac
}

required_payload_executable_description() {
  case "$1" in
    service-manager)
      printf 'service-manager at service-manager or target/release/service-manager'
      ;;
    openhouse-connect)
      printf 'cc-connect at cc-connect'
      ;;
    *)
      return 1
      ;;
  esac
}

payload_dir_needs_refresh() {
  local source="$1"
  local payload_name="$2"

  case "$payload_name" in
    openhouse-connect)
      [ -f "$source/scripts/register-service.sh" ] \
        && grep -Fq 'CC_CONNECT_BRIDGE_PORT' "$source/scripts/register-service.sh" \
        && grep -Fq 'CC_CONNECT_MANAGEMENT_PORT' "$source/scripts/register-service.sh" \
        && grep -Fq 'CC_CONNECT_WEBHOOK_PORT' "$source/scripts/register-service.sh" \
        && grep -Fq 'detect_claude_cli' "$source/scripts/register-service.sh" \
        && grep -Fq 'claudecode' "$source/scripts/register-service.sh" \
        && grep -Fq -- '--config' "$source/scripts/register-service.sh" \
        || return 0
      ;;
    smallphone)
      [ -f "$source/scripts/install.sh" ] \
        && grep -Fq 'SMALLPHONE_SKIP_DEP_INSTALL' "$source/scripts/install.sh" \
        && ! grep -Fq 'Offline/local dependency installation is disabled' "$source/scripts/install.sh" \
        || return 0
      ;;
    pi-web)
      [ -f "$source/runtime/pi-web/server.js" ] || return 0
      [ -f "$source/bin/openhouse-pi-web-start" ] || return 0
      [ -f "$source/scripts/install.sh" ] || return 0
      [ -f "$source/scripts/check.sh" ] || return 0

      if [ -d "$source/packages" ]; then
        for artifact in "$source"/packages/agegr-pi-web*; do
          [ -e "$artifact" ] && return 0
        done
      fi

      if grep -Eq 'npm[[:space:]]+install|npm[[:space:]]+root[[:space:]]+-g|agegr-pi-web|\.tgz' \
        "$source/scripts/install.sh" "$source/scripts/check.sh" 2>/dev/null; then
        return 0
      fi

      grep -Fq 'OPENHOUSE_PI_WEB_RUNTIME_DIR' "$source/bin/openhouse-pi-web-start" \
        && grep -Fq 'exec node server.js' "$source/bin/openhouse-pi-web-start" \
        || return 0
      ;;
  esac

  return 1
}

validate_payload_source() {
  local name="$1"
  local source="$2"
  local payload_name="${3:-}"
  local required_pattern
  local required_description
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
    if required_payload_executable "$payload_name" >/dev/null; then
      required_description="$(required_payload_executable_description "$payload_name")"
      if ! payload_dir_contains_executable "$source" "$payload_name"; then
        warn "$name: APK payload directory must contain executable $required_description: $source"
        return 1
      fi
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
    if required_payload_executable "$payload_name" >/dev/null; then
      required_pattern="$(required_payload_executable_pattern "$payload_name")"
      required_description="$(required_payload_executable_description "$payload_name")"
      if ! payload_archive_contains_executable "$source" "$required_pattern"; then
        warn "$name: APK payload archive must contain executable $required_description: $source"
        return 1
      fi
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
  if ! validate_payload_source "$name" "$source" "$payload_name"; then
    return 1
  fi

  if [ -f "$dir/scripts/install.sh" ] && [ -f "$dir/scripts/check.sh" ]; then
    if [ "$force_payload_refresh" = "1" ]; then
      log "$name: APK payload 刷新已开启，将从当前 APK bundle 覆盖刷新：$dir"
    elif required_payload_executable "$payload_name" >/dev/null \
      && ! payload_dir_contains_executable "$dir" "$payload_name"; then
      log "$name: 已存在安装目录但缺少 APK bundle 必需二进制，将从 payload 刷新：$dir"
    elif payload_dir_needs_refresh "$dir" "$payload_name"; then
      log "$name: 已存在安装目录但组件脚本不是当前 APK bundle 合同，将从 payload 刷新：$dir"
    else
      log "$name: 已存在安装目录，已验证 APK payload 可用：$dir"
      return 0
    fi
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

local_component_binary_path() {
  local payload_name="$1"
  local dir="$2"
  local candidate

  case "$payload_name" in
    service-manager)
      for candidate in "$dir/service-manager" "$dir/target/release/service-manager"; do
        if [ -f "$candidate" ] && [ -x "$candidate" ]; then
          printf '%s\n' "$candidate"
          return 0
        fi
      done
      ;;
    openhouse-connect)
      candidate="$dir/cc-connect"
      if [ -f "$candidate" ] && [ -x "$candidate" ]; then
        if "$candidate" --version >/dev/null 2>&1 || "$candidate" --help >/dev/null 2>&1; then
          printf '%s\n' "$candidate"
          return 0
        fi
      fi
      ;;
    *)
      return 1
      ;;
  esac

  return 1
}

validate_bundle_local_install_source() {
  local name="$1"
  local payload_name="$2"
  local dir="$3"
  local required_binary
  local local_binary

  [ "$component_source_mode" = "bundle" ] || return 0

  if ! required_binary="$(required_payload_executable "$payload_name")"; then
    return 0
  fi

  if ! local_binary="$(local_component_binary_path "$payload_name" "$dir")"; then
    warn "$name: bundle 模式要求安装目录包含可执行 $required_binary；拒绝运行 scripts/install.sh 以避免 GitHub/npm/go fallback：$dir"
    return 1
  fi

  log "$name: bundle/local 安装将使用 payload 内可执行文件：$local_binary"
}

run_repo_script_command() {
  local payload_name="$1"
  local dir="$2"
  local script="$3"
  local local_binary

  case "$component_source_mode:$payload_name:$script" in
    bundle:service-manager:scripts/install.sh)
      local_binary="$(local_component_binary_path "$payload_name" "$dir")"
      run_logged env \
        SMALLPHONEAI_OFFLINE_INSTALL=1 \
        SMALLPHONEAI_LOCAL_INSTALL=1 \
        SERVICE_MANAGER_INSTALL_MODE=local \
        "./$script" "$local_binary"
      ;;
    bundle:openhouse-connect:scripts/install.sh)
      run_logged env \
        SMALLPHONEAI_OFFLINE_INSTALL=1 \
        SMALLPHONEAI_LOCAL_INSTALL=1 \
        CC_CONNECT_INSTALL_MODE=local \
        CC_CONNECT_LOCAL_INSTALL=1 \
        CC_CONNECT_OFFLINE_INSTALL=1 \
        "./$script"
      ;;
    bundle:smallphone:scripts/install.sh)
      ensure_smallphone_node_runtime
      run_logged env \
        SMALLPHONEAI_OFFLINE_INSTALL=1 \
        SMALLPHONEAI_LOCAL_INSTALL=1 \
        NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org/}" \
        "./$script"
      ;;
    *)
      run_logged "./$script"
      ;;
  esac
}

run_repo_script() {
  local name="$1"
  local dir="$2"
  local script="$3"
  local required="$4"
  local payload_name="${5:-}"
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
  if ! (cd "$dir" && run_repo_script_command "$payload_name" "$dir" "$script"); then
    if [ "$required" = "1" ]; then
      warn "$name: $script 执行失败"
      failures=$((failures + 1))
    else
      warn "$name: 可选入口 $script 执行失败，继续。"
    fi
  fi
}

node_major_version() {
  node -p 'process.versions.node.split(".")[0]' 2>/dev/null || printf 0
}

ensure_smallphone_node_runtime() {
  local major
  export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"
  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    major="$(node_major_version)"
    case "$major" in
      ''|*[!0-9]*)
        major=0
        ;;
    esac
    if [ "$major" -ge 24 ]; then
      log "SmallPhone Node runtime 已可用：$(node -v)"
      return 0
    fi
    warn "SmallPhone 需要 Node >=24，当前为 $(node -v 2>/dev/null || printf unknown)，将安装本地 Node 24。"
  fi

  install_node24_runtime
}

node_arch() {
  case "$(uname -m)" in
    aarch64|arm64)
      printf 'arm64'
      ;;
    x86_64|amd64)
      printf 'x64'
      ;;
    armv7l|armv7*)
      printf 'armv7l'
      ;;
    *)
      return 1
      ;;
  esac
}

install_node24_runtime() {
  local arch
  local node_root="$HOME/.local/node"
  local node_tmp="$HOME/.local/node-download"
  local node_dist_base="${SMALLPHONEAI_NODE_DIST_BASE:-https://nodejs.org/dist/latest-v24.x}"
  local node_tarball

  if ! command -v curl >/dev/null 2>&1; then
    warn "缺少 curl，无法安装 Node 24 runtime。"
    return 1
  fi
  if ! arch="$(node_arch)"; then
    warn "不支持的 Node runtime 架构：$(uname -m)"
    return 1
  fi

  if command -v apt >/dev/null 2>&1; then
    apt install -y ca-certificates xz-utils >/dev/null 2>&1 || true
  fi

  mkdir -p "$node_root" "$node_tmp"
  log "正在从 Node 官方源安装 Node 24 runtime（不访问 GitHub）：$node_dist_base"
  node_tarball="$(curl -fsSL --connect-timeout 20 --retry 3 --retry-delay 2 --retry-all-errors "$node_dist_base/SHASUMS256.txt" \
    | awk -v arch="linux-$arch.tar.xz" '$2 ~ arch "$" { print $2; exit }')"
  if [ -z "$node_tarball" ]; then
    warn "无法解析 Node 24 linux-$arch tarball。"
    return 1
  fi

  curl -fL --connect-timeout 20 --retry 3 --retry-delay 2 --retry-all-errors \
    "$node_dist_base/$node_tarball" -o "$node_tmp/$node_tarball"
  rm -rf "$node_root"
  mkdir -p "$node_root"
  tar -xJf "$node_tmp/$node_tarball" -C "$node_root" --strip-components=1
  export PATH="$node_root/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"
  mkdir -p "$HOME/.npm-global/bin"
  npm config set prefix "$HOME/.npm-global"
  npm config set registry "${NPM_REGISTRY:-https://registry.npmjs.org/}"
  node -v
  npm -v
}

find_service_manager_binary_for_registration() {
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

resolve_service_manager_token() {
  local sm_bin="$1"
  local token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  if [ -z "$token" ]; then
    token="$("$sm_bin" token show 2>/dev/null | tr -d '\r\n' || true)"
  fi
  printf '%s' "$token"
}

service_manager_auth_ready() {
  local token="$1"
  local work_dir
  local curl_cfg

  [ -n "$token" ] || return 1
  command -v curl >/dev/null 2>&1 || return 1
  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-sm-auth.XXXXXX")" || return 1
  curl_cfg="$work_dir/curl.cfg"
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$curl_cfg"
  curl -q -fsS --max-time 3 -K "$curl_cfg" "$service_manager_url/api/v1/services" >/dev/null 2>&1
  status=$?
  rm -rf "$work_dir" >/dev/null 2>&1 || true
  return "$status"
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

service_manager_listen_addr() {
  local value="$service_manager_url"
  case "$value" in
    http://*) value="${value#http://}" ;;
    https://*) value="${value#https://}" ;;
    "") value="$service_manager_bind" ;;
  esac
  value="${value%%/*}"
  [ -n "$value" ] || value="$service_manager_bind"
  printf '%s' "$value"
}

write_openhouse_service_manager_config() {
  local target="$1"
  local token="$2"
  local listen_addr="$3"
  local dir
  local tmp
  local token_json
  local listen_json

  case "$target" in
    */.config/service-manager/config.json)
      warn "拒绝写入旧 service-manager 配置路径：$target"
      return 1
      ;;
  esac

  dir="$(dirname "$target")"
  if ! mkdir -p "$dir"; then
    warn "无法创建 OpenHouse service-manager 配置目录：$dir"
    return 1
  fi

  token_json="$(json_escape "$token")"
  listen_json="$(json_escape "$listen_addr")"
  tmp="$target.tmp.$$"
  if ! cat > "$tmp" <<EOF
{
  "auth_token": "$token_json",
  "listen_addr": "$listen_json"
}
EOF
  then
    warn "无法写入 OpenHouse service-manager 临时配置：$tmp"
    rm -f "$tmp" >/dev/null 2>&1 || true
    return 1
  fi

  chmod 600 "$tmp" >/dev/null 2>&1 || true
  if ! mv "$tmp" "$target"; then
    warn "无法更新 OpenHouse service-manager 配置：$target"
    rm -f "$tmp" >/dev/null 2>&1 || true
    return 1
  fi
}

sync_openhouse_service_manager_config() {
  local token="$1"
  local listen_addr="${2:-}"
  local target
  local wrote=0
  local failed=0

  if [ -z "$token" ]; then
    warn "service-manager token 为空，跳过同步到 OpenHouse 专用配置。"
    return 1
  fi
  [ -n "$listen_addr" ] || listen_addr="$(service_manager_listen_addr)"

  for target in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json" \
    "$HOME/.config/openhouseai/service-manager/config.json"; do
    [ -n "$target" ] || continue
    case "$target" in
      /data/data/com.termux/files/home/*)
        [ -d "/data/data/com.termux/files/home" ] || continue
        ;;
    esac
    if write_openhouse_service_manager_config "$target" "$token" "$listen_addr"; then
      wrote=1
    else
      failed=1
    fi
  done

  if [ "$wrote" = "1" ]; then
    log "已同步 service-manager token 到 OpenHouse 专用配置：listen_addr=$listen_addr"
  else
    warn "未能同步 service-manager token 到任何 OpenHouse 专用配置路径。"
  fi
  [ "$failed" = "0" ] || return 1
}

stop_service_manager_for_registration() {
  local self="$$"
  ps -eo pid=,comm=,args= 2>/dev/null | while read -r pid comm args; do
    [ -n "$pid" ] || continue
    [ "$pid" = "$self" ] && continue
    case "$comm $args" in
      *service-manager*" serve "*|*service-manager*" serve")
        kill "$pid" >/dev/null 2>&1 || true
        ;;
    esac
  done
  sleep 1
}

start_service_manager_for_registration() {
  local sm_bin="$1"
  mkdir -p "$HOME/.smallphoneai/logs"
  log "正在启动 service-manager 以注册 SmallPhoneAI 组件：$service_manager_bind"
  nohup "$sm_bin" serve --bind "$service_manager_bind" > "$HOME/.smallphoneai/logs/service-manager.log" 2>&1 < /dev/null &
  for _ in $(seq 1 30); do
    if service_manager_health_ready; then
      log "service-manager 已启动：$service_manager_url"
      return 0
    fi
    sleep 1
  done
  warn "service-manager 未能在注册前启动。"
  return 1
}

ensure_service_manager_registration_context() {
  local sm_bin
  local token

  sm_bin="$(find_service_manager_binary_for_registration || true)"
  if [ -z "$sm_bin" ]; then
    warn "未找到 service-manager，可执行文件缺失，跳过注册上下文准备。"
    return 1
  fi
  export PATH="$(dirname "$sm_bin"):$PATH"

  if ! service_manager_health_ready; then
    start_service_manager_for_registration "$sm_bin" || return 1
  fi

  token="$(resolve_service_manager_token "$sm_bin")"
  if ! service_manager_auth_ready "$token"; then
    warn "service-manager token 与当前运行实例不匹配，重启本地 service-manager。"
    stop_service_manager_for_registration
    start_service_manager_for_registration "$sm_bin" || return 1
    token="$(resolve_service_manager_token "$sm_bin")"
  fi

  if [ -z "$token" ] || ! service_manager_auth_ready "$token"; then
    warn "无法获得可用的 service-manager token，后续注册可能失败。"
    return 1
  fi

  export SERVICE_MANAGER_URL="$service_manager_url"
  export SERVICE_MANAGER_TOKEN="$token"
  export SMALLPHONE_SERVICE_MANAGER_TOKEN="${SMALLPHONE_SERVICE_MANAGER_TOKEN:-$token}"
  sync_openhouse_service_manager_config "$token" "$(service_manager_listen_addr)" || true
  log "service-manager 注册上下文已就绪。"
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

  if ! validate_bundle_local_install_source "$name" "$payload_name" "$dir"; then
    if [ "$required" = "1" ]; then
      failures=$((failures + 1))
    fi
    return 0
  fi

  run_repo_script "$name" "$dir" "scripts/install.sh" "$required" "$payload_name"
  run_repo_script "$name" "$dir" "scripts/check.sh" "$required" "$payload_name"
  run_repo_script "$name" "$dir" "scripts/register-service.sh" "0" "$payload_name"
}

service_manager_dir="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-$(default_path service-manager)}"
cc_connect_dir="${SMALLPHONEAI_CC_CONNECT_DIR:-$(default_path openhouse-connect)}"
smallphone_dir="${SMALLPHONEAI_SMALLPHONE_DIR:-$(default_path smallphone-active)}"
pi_agent_dir="${OPENHOUSE_PI_AGENT_DIR:-${SMALLPHONEAI_PI_AGENT_DIR:-$(default_path pi-agent)}}"
pi_web_dir="${OPENHOUSE_PI_WEB_DIR:-${SMALLPHONEAI_PI_WEB_DIR:-$(default_path pi-web)}}"
service_manager_bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
service_manager_url="${SERVICE_MANAGER_URL:-http://$service_manager_bind}"

log "SmallPhoneAI 运行组件入口由各子仓库维护。"
log "当前运行环境：$(detect_smallphoneai_runtime)"
log "组件仓库根目录：$repo_root"
log "组件来源模式：$component_source_mode"
if [ "$component_source_mode" = "bundle" ]; then
  log "APK payload 根目录：$payload_root"
  if [ "$force_payload_refresh" = "1" ]; then
    log "APK payload 强制刷新：开启"
  fi
fi
if [ -n "$component_targets" ]; then
  log "本次仅处理指定组件：$component_targets"
else
  log "本次处理默认组件：service-manager、cc-connect/openhouse-connect、pi-agent、pi-web；SmallPhone 作为兼容组件尝试安装。"
fi

validate_component_targets

if should_run_component "service-manager"; then
  run_component "service-manager" "$service_manager_dir" "${SMALLPHONEAI_SERVICE_MANAGER_GIT_URL:-https://github.com/jiwuyou/service-manager.git}" "1" "service-manager"
  ensure_service_manager_registration_context || true
fi
if should_run_component "cc-connect"; then
  run_component "cc-connect/openhouse-connect" "$cc_connect_dir" "${SMALLPHONEAI_CC_CONNECT_GIT_URL:-https://github.com/jiwuyou/openhouse-connect-fresh.git}" "1" "openhouse-connect"
fi
if should_run_component "smallphone"; then
  run_component "SmallPhone compatibility service" "$smallphone_dir" "${SMALLPHONEAI_SMALLPHONE_GIT_URL:-https://github.com/jiwuyou/wuxian-smallphone.git}" "0" "smallphone"
fi
if should_run_component "pi-agent"; then
  run_component "pi-agent" "$pi_agent_dir" "${OPENHOUSE_PI_AGENT_GIT_URL:-}" "1" "pi-agent"
fi
if should_run_component "pi-web"; then
  run_component "pi-web" "$pi_web_dir" "${OPENHOUSE_PI_WEB_GIT_URL:-}" "1" "pi-web"
fi

if [ -n "${SERVICE_MANAGER_TOKEN:-}" ]; then
  sync_openhouse_service_manager_config "$SERVICE_MANAGER_TOKEN" "$(service_manager_listen_addr)" || true
fi

if [ "$failures" -ne 0 ]; then
  if [ "$strict" = "1" ]; then
    warn "SmallPhoneAI 运行组件存在 $failures 个失败项。"
    exit 1
  fi
  warn "SmallPhoneAI 运行组件存在 $failures 个失败项；SMALLPHONEAI_COMPONENTS_STRICT=0，继续。"
fi

log "SmallPhoneAI 运行组件安装/检查/注册阶段完成。"
