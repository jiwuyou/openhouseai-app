#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
}

ensure_tmpdir() {
  local candidate="${TMPDIR:-}"
  local termux_native=0
  if [ -n "${PREFIX:-}" ] && [ -d "/data/data/com.termux/files" ]; then
    termux_native=1
    if [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release; then
      termux_native=0
    fi
  fi

  if [ "$termux_native" = "1" ]; then
    case "$candidate" in
      ""|/tmp|/tmp/*|/var/tmp|/var/tmp/*)
        TMPDIR="${PREFIX:-/data/data/com.termux/files/usr}/tmp"
        ;;
      *)
        TMPDIR="$candidate"
        ;;
    esac
  elif [ -z "$candidate" ]; then
    if [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/tmp" ]; then
      TMPDIR="$PREFIX/tmp"
    else
      TMPDIR="${HOME:-.}/.tmp"
    fi
  fi

  export TMPDIR
  if ! mkdir -p "$TMPDIR" 2>/dev/null; then
    if [ "$termux_native" = "1" ]; then
      TMPDIR="${HOME:-.}/.tmp"
      export TMPDIR
      mkdir -p "$TMPDIR"
    else
      mkdir -p "$TMPDIR"
    fi
  fi
}

run_logged() {
  log "+ $*"
  "$@"
}

run_with_service_manager_auth() {
  local token="$1"
  local compatibility_token="${SMALLPHONE_SERVICE_MANAGER_TOKEN:-$token}"
  shift
  (
    export SERVICE_MANAGER_TOKEN="$token"
    export SMALLPHONE_SERVICE_MANAGER_TOKEN="$compatibility_token"
    "$@"
  )
}

load_service_manager_token_file() {
  local token_file="${SMALLPHONEAI_SERVICE_MANAGER_TOKEN_FILE:-}"
  local token

  [ -n "$token_file" ] || return 0
  unset SMALLPHONEAI_SERVICE_MANAGER_TOKEN_FILE
  if [ ! -r "$token_file" ]; then
    warn "service-manager token 文件不可读。"
    return 1
  fi
  if ! token="$(cat -- "$token_file")"; then
    rm -f -- "$token_file" >/dev/null 2>&1 || true
    rmdir -- "$(dirname -- "$token_file")" >/dev/null 2>&1 || true
    warn "service-manager token 文件读取失败。"
    return 1
  fi
  rm -f -- "$token_file" >/dev/null 2>&1 || true
  rmdir -- "$(dirname -- "$token_file")" >/dev/null 2>&1 || true
  [ -n "$token" ] || {
    warn "service-manager token 文件为空。"
    return 1
  }
  if [ -z "${SERVICE_MANAGER_TOKEN:-}" ]; then
    export SERVICE_MANAGER_TOKEN="$token"
  fi
  if [ -z "${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}" ]; then
    export SMALLPHONE_SERVICE_MANAGER_TOKEN="${SERVICE_MANAGER_TOKEN:-$token}"
  fi
}

create_ubuntu_service_manager_token_file() {
  local token="$1"
  [ -n "$token" ] || return 1
  printf '%s\n' "$token" \
    | env -u SERVICE_MANAGER_TOKEN -u SMALLPHONE_SERVICE_MANAGER_TOKEN \
      proot-distro login ubuntu -- sh -c '
        set -eu
        umask 077
        auth_dir="$(mktemp -d "${TMPDIR:-/tmp}/smallphoneai-sm-token.XXXXXX")"
        auth_file="$auth_dir/token"
        published=0
        cleanup_auth_file() {
          if [ "$published" != "1" ]; then
            rm -rf -- "$auth_dir" >/dev/null 2>&1 || true
          fi
        }
        trap cleanup_auth_file EXIT
        trap "exit 1" INT HUP TERM
        cat > "$auth_file"
        chmod 600 "$auth_file"
        published=1
        printf "%s\n" "$auth_file"
      '
}

cleanup_ubuntu_service_manager_token_file() {
  local token_file="${1:-}"
  [ -n "$token_file" ] || return 0
  env -u SERVICE_MANAGER_TOKEN -u SMALLPHONE_SERVICE_MANAGER_TOKEN \
    proot-distro login ubuntu -- sh -c '
      rm -f -- "$1" >/dev/null 2>&1 || true
      rmdir -- "$(dirname -- "$1")" >/dev/null 2>&1 || true
    ' sh "$token_file" >/dev/null 2>&1 || true
}

ensure_tmpdir
if [ -n "${SMALLPHONEAI_SERVICE_MANAGER_TOKEN_FILE:-}" ]; then
  load_service_manager_token_file || exit 1
fi

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

normalize_bootstrap_target() {
  case "$1" in
    openhouse-system|main-system|system)
      printf 'openhouse-system'
      ;;
    openhouse-connect)
      printf 'cc-connect'
      ;;
    github|github-helper|github-config)
      printf 'github-config-helper'
      ;;
    hermes-agent|hermes-webui)
      printf 'hermes'
      ;;
    wuyou|wuyou-web|wuyou-agent)
      printf 'wuyou'
      ;;
    pi|pi-agent)
      printf 'pi-agent'
      ;;
    pi-web)
      printf 'pi-web'
      ;;
    openhouse-web|web-shell|openhouse-shell)
      printf 'openhouse-web'
      ;;
    *)
      printf '%s' "$1"
      ;;
  esac
}

bootstrap_target_requested() {
  local target="$1"
  local rest item normalized
  rest="${SMALLPHONEAI_COMPONENT_TARGETS:-}"
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
    normalized="$(normalize_bootstrap_target "$item")"
    [ "$normalized" = "$target" ] && return 0
  done

  return 1
}

append_csv() {
  local current="$1"
  local item="$2"
  if [ -z "$item" ]; then
    printf '%s\n' "$current"
  elif [ -z "$current" ]; then
    printf '%s\n' "$item"
  else
    printf '%s,%s\n' "$current" "$item"
  fi
}

openhouse_pi_runtime() {
  local runtime="${OPENHOUSE_PI_RUNTIME:-${SMALLPHONEAI_PI_RUNTIME:-termux}}"
  runtime="$(printf '%s' "$runtime" | tr '[:upper:]' '[:lower:]')"
  case "$runtime" in
    ubuntu|proot|ubuntu-proot)
      printf 'ubuntu'
      ;;
    *)
      printf 'termux'
      ;;
  esac
}

default_component_targets() {
  printf '%s\n' "service-manager,openhouse-web,pi-agent,pi-web,github-config-helper,cc-connect,smallphone,hermes"
}

bootstrap_targets_for_runtime() {
  local wanted_runtime="$1"
  local rest item normalized out pi_runtime
  rest="${SMALLPHONEAI_COMPONENT_TARGETS:-}"
  if [ -z "$rest" ]; then
    rest="$(default_component_targets)"
  fi

  out=""
  pi_runtime="$(openhouse_pi_runtime)"
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
    normalized="$(normalize_bootstrap_target "$item")"
    [ -n "$normalized" ] || continue
    if [ "$wanted_runtime" = "termux" ]; then
      case "$normalized" in
        openhouse-web)
          out="$(append_csv "$out" "$normalized")"
          ;;
        pi-agent|pi-web)
          [ "$pi_runtime" = "termux" ] && out="$(append_csv "$out" "$normalized")"
          ;;
      esac
    else
      case "$normalized" in
        service-manager|openhouse-system|openhouse-web)
          ;;
        pi-agent|pi-web)
          [ "$pi_runtime" = "ubuntu" ] && out="$(append_csv "$out" "$normalized")"
          ;;
        *)
          out="$(append_csv "$out" "$normalized")"
          ;;
      esac
    fi
  done
  printf '%s\n' "$out"
}

termux_service_manager_config_path() {
  local termux_home
  termux_home="${OPENHOUSEAI_TERMUX_HOME:-$HOME}"
  printf '%s\n' "${SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH:-${SERVICE_MANAGER_CONFIG_PATH:-$termux_home/.config/openhouseai/service-manager/config.json}}"
}

component_binary_current_env_executable() {
  local payload_name="$1"
  local binary="$2"

  [ -f "$binary" ] && [ -x "$binary" ] || return 1

  case "$payload_name" in
    service-manager)
      [ "$("$binary" --version 2>/dev/null | tr -d '\r\n')" = "service-manager 0.3.0" ]
      ;;
    openhouse-connect)
      "$binary" --version >/dev/null 2>&1 || "$binary" --help >/dev/null 2>&1
      ;;
    wuyou)
      "$binary" --help >/dev/null 2>&1
      ;;
    *)
      return 1
      ;;
  esac
}

service_manager_binary_current_env_executable() {
  component_binary_current_env_executable service-manager "$1"
}

find_termux_service_manager_binary() {
  local candidate
  if command -v service-manager >/dev/null 2>&1; then
    candidate="$(command -v service-manager)"
    if service_manager_binary_current_env_executable "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
    warn "发现 service-manager 但当前 Termux 环境不可执行，将忽略并继续查找 payload/local binary：$candidate"
  fi
  for candidate in \
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/target/release/service-manager"; do
    if service_manager_binary_current_env_executable "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    elif [ -e "$candidate" ]; then
      warn "发现 service-manager 但当前环境不可执行，将等待 payload/local 刷新：$candidate"
    fi
  done
  return 1
}

termux_service_manager_ready() {
  local url="${SERVICE_MANAGER_URL:-http://${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}}"
  command -v curl >/dev/null 2>&1 || return 1
  curl -fsS --max-time 2 "$url/api/v1/health" >/dev/null 2>&1
}

termux_service_manager_config_token() {
  local sm_bin cfg
  sm_bin="$(find_termux_service_manager_binary || true)"
  cfg="$(termux_service_manager_config_path)"
  [ -n "$sm_bin" ] || return 1
  "$sm_bin" token show --config "$cfg" 2>/dev/null | head -n 1 | tr -d '\r\n'
}

termux_service_manager_auth_ready() (
  local token="$1"
  local url="${SERVICE_MANAGER_URL:-http://${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}}"
  local work_dir curl_cfg

  [ -n "$token" ] || return 1
  command -v curl >/dev/null 2>&1 || return 1
  umask 077
  work_dir="$(mktemp -d "$TMPDIR/smallphoneai-sm-auth.XXXXXX")" || return 1
  trap 'rm -rf "$work_dir" >/dev/null 2>&1 || true' EXIT
  trap 'exit 1' INT HUP TERM
  curl_cfg="$work_dir/curl.cfg"
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$curl_cfg"
  chmod 600 "$curl_cfg"
  curl -q -fsS --max-time 3 -K "$curl_cfg" "$url/api/v1/services" >/dev/null 2>&1
)

termux_service_manager_ready_for_registration() {
  local token
  termux_service_manager_instance_matches_expected || return 1
  termux_service_manager_ready || return 1
  token="$(termux_service_manager_config_token || true)"
  [ -n "$token" ] || return 1
  termux_service_manager_auth_ready "$token"
}

termux_service_manager_serve_pids() {
  local proc comm args
  for proc in /proc/[0-9]*; do
    [ -r "$proc/comm" ] && [ -r "$proc/cmdline" ] || continue
    comm="$(cat "$proc/comm" 2>/dev/null || true)"
    [ "$comm" = "service-manager" ] || continue
    args="$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null || true)"
    printf '%s\n' "$args" | grep -Fqx -- "serve" || continue
    printf '%s\n' "${proc##*/}"
  done
}

termux_service_manager_instance_matches_expected() {
  local cfg bind sm_bin expected_exe pid args actual_exe total=0 matched=0
  cfg="$(termux_service_manager_config_path)"
  bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
  sm_bin="$(find_termux_service_manager_binary || true)"
  [ -n "$sm_bin" ] || return 1
  expected_exe="$(readlink -f "$sm_bin" 2>/dev/null || true)"
  [ -n "$expected_exe" ] || return 1
  for pid in $(termux_service_manager_serve_pids); do
    total=$((total + 1))
    args="$(tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    actual_exe="$(readlink "/proc/$pid/exe" 2>/dev/null || true)"
    if [ "$actual_exe" = "$expected_exe" ] \
      && printf '%s\n' "$args" | grep -Fqx -- "--config" \
      && printf '%s\n' "$args" | grep -Fqx -- "$cfg" \
      && printf '%s\n' "$args" | grep -Fqx -- "--bind" \
      && printf '%s\n' "$args" | grep -Fqx -- "$bind"; then
      matched=$((matched + 1))
    fi
  done
  [ "$total" -eq 1 ] && [ "$matched" -eq 1 ]
}

stop_stale_termux_service_manager() {
  local pid pids

  if command -v sv >/dev/null 2>&1; then
    sv down service-manager >/dev/null 2>&1 || true
  fi
  pids="$(termux_service_manager_serve_pids)"
  for pid in $pids; do
    kill "$pid" >/dev/null 2>&1 || true
  done
  for _ in $(seq 1 10); do
    [ -z "$(termux_service_manager_serve_pids)" ] && return 0
    sleep 1
  done
  for pid in $(termux_service_manager_serve_pids); do
    kill -9 "$pid" >/dev/null 2>&1 || true
  done
  sleep 1
  [ -z "$(termux_service_manager_serve_pids)" ]
}

start_termux_service_manager_for_registration() {
  local bind url cfg sm_bin
  bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
  url="${SERVICE_MANAGER_URL:-http://$bind}"
  cfg="$(termux_service_manager_config_path)"

  if termux_service_manager_ready_for_registration; then
    return 0
  fi
  if [ -n "$(termux_service_manager_serve_pids)" ] || termux_service_manager_ready; then
    warn "检测到非预期或认证不匹配的 service-manager；将按 OpenHouse 专用 config 重启。"
    stop_stale_termux_service_manager || return 1
  fi

  sm_bin="$(find_termux_service_manager_binary || true)"
  [ -n "$sm_bin" ] || return 1

  if command -v sv >/dev/null 2>&1 && [ -d "${PREFIX:-/data/data/com.termux/files/usr}/var/service" ]; then
    "$sm_bin" install-service --config "$cfg" --bind "$bind" >/dev/null 2>&1 || true
    sv up service-manager >/dev/null 2>&1 || true
    for _ in $(seq 1 10); do
      termux_service_manager_ready_for_registration && return 0
      sleep 1
    done
    sv down service-manager >/dev/null 2>&1 || true
    stop_stale_termux_service_manager || return 1
  fi

  mkdir -p "$HOME/.smallphoneai/logs" "$(dirname "$cfg")"
  log "正在 Termux native 启动 service-manager 以注册组件：$url"
  nohup "$sm_bin" serve --config "$cfg" --bind "$bind" > "$HOME/.smallphoneai/logs/service-manager.log" 2>&1 < /dev/null &
  for _ in $(seq 1 20); do
    termux_service_manager_ready_for_registration && return 0
    sleep 1
  done
  return 1
}

resolve_termux_service_manager_token() {
  local token sm_bin cfg
  token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  if [ -n "$token" ]; then
    printf '%s\n' "$token"
    return 0
  fi
  sm_bin="$(find_termux_service_manager_binary || true)"
  cfg="$(termux_service_manager_config_path)"
  if [ -n "$sm_bin" ]; then
    "$sm_bin" token show --config "$cfg" 2>/dev/null | head -n 1 | tr -d '\r\n' || true
  fi
}

resolve_bootstrap_root() {
  local script_path

  if [ -n "${SMALLPHONEAI_ROOT:-}" ]; then
    printf '%s\n' "$SMALLPHONEAI_ROOT"
    return 0
  fi

  script_path="${BASH_SOURCE[0]:-${0:-}}"
  case "$script_path" in
    ""|bash|sh|-bash|-sh)
      if [ -f "$PWD/scripts/50-install-runtime-components.sh" ]; then
        script_path="$PWD/scripts/50-install-runtime-components.sh"
      elif [ -f "$PWD/bootstrap/scripts/50-install-runtime-components.sh" ]; then
        script_path="$PWD/bootstrap/scripts/50-install-runtime-components.sh"
      else
        script_path="$PWD/50-install-runtime-components.sh"
      fi
      ;;
  esac

  cd "$(dirname "$script_path")/.." && pwd
}

bootstrap_root="$(resolve_bootstrap_root)"

if is_termux && [ "${SMALLPHONEAI_RUNTIME_COMPONENTS_IN_UBUNTU:-1}" = "1" ]; then
  termux_pi_targets="$(bootstrap_targets_for_runtime termux)"
  ubuntu_targets="$(bootstrap_targets_for_runtime ubuntu)"
  native_token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  native_config="$(termux_service_manager_config_path)"

  log "正在 Termux native 部署 openhouse-system 主系统 CLI。"
  SMALLPHONEAI_RUNTIME_COMPONENTS_IN_UBUNTU=0 \
    SMALLPHONEAI_ROOT="$bootstrap_root" \
    SMALLPHONEAI_COMPONENT_TARGETS=openhouse-system \
    SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH="$native_config" \
    SERVICE_MANAGER_CONFIG_PATH="$native_config" \
    bash -s < "$0"

  if bootstrap_target_requested "service-manager"; then
    log "正在 Termux native 安装 service-manager 控制面。"
    SMALLPHONEAI_RUNTIME_COMPONENTS_IN_UBUNTU=0 \
      SMALLPHONEAI_ROOT="$bootstrap_root" \
      SMALLPHONEAI_COMPONENT_TARGETS=service-manager \
      SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH="$native_config" \
      SERVICE_MANAGER_CONFIG_PATH="$native_config" \
      bash -s < "$0"
    start_termux_service_manager_for_registration || warn "Termux native service-manager 暂未就绪；业务组件会在启动阶段再次刷新注册。"
    native_token="$(resolve_termux_service_manager_token || true)"
  elif [ -n "$ubuntu_targets" ] || [ -n "$termux_pi_targets" ]; then
    start_termux_service_manager_for_registration || warn "Termux native service-manager 暂未就绪；业务组件可能只能完成安装/检查，注册会在启动阶段重试。"
    native_token="$(resolve_termux_service_manager_token || true)"
  fi

  if [ -n "$termux_pi_targets" ]; then
    log "正在 Termux native 安装、检查并注册 pi 常驻组件：$termux_pi_targets"
    run_with_service_manager_auth "${SERVICE_MANAGER_TOKEN:-$native_token}" env \
      SMALLPHONEAI_RUNTIME_COMPONENTS_IN_UBUNTU=0 \
      SMALLPHONEAI_ROOT="$bootstrap_root" \
      SMALLPHONEAI_COMPONENT_TARGETS="$termux_pi_targets" \
      SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH="$native_config" \
      SERVICE_MANAGER_CONFIG_PATH="$native_config" \
      SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-http://${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}}" \
      bash -s < "$0"
  fi

  if [ -z "$ubuntu_targets" ]; then
    log "本次组件目标不需要进入 Ubuntu。"
    exit 0
  fi

  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    ubuntu_runtime_home="${SMALLPHONEAI_UBUNTU_HOME:-${OPENHOUSEAI_UBUNTU_HOME:-/root}}"
    ubuntu_repo_root="${SMALLPHONEAI_UBUNTU_COMPONENT_REPO_ROOT:-${OPENHOUSEAI_UBUNTU_COMPONENT_REPO_ROOT:-$ubuntu_runtime_home/smallphoneai-repos}}"
    log "正在 Ubuntu 内安装、检查并注册业务组件：$ubuntu_targets"
    if (
      ubuntu_token_file=""
      trap 'cleanup_ubuntu_service_manager_token_file "$ubuntu_token_file"' EXIT
      trap 'exit 1' INT HUP TERM
      if [ -n "$native_token" ]; then
        ubuntu_token_file="$(create_ubuntu_service_manager_token_file "$native_token")" || {
          warn "无法为 Ubuntu 创建 service-manager 临时认证文件。"
          exit 1
        }
      fi
      env -u SERVICE_MANAGER_TOKEN -u SMALLPHONE_SERVICE_MANAGER_TOKEN \
        proot-distro login ubuntu -- env \
        HOME="$ubuntu_runtime_home" \
        SMALLPHONEAI_ROOT="$bootstrap_root" \
        SMALLPHONEAI_UBUNTU_HOME="$ubuntu_runtime_home" \
        OPENHOUSEAI_UBUNTU_HOME="$ubuntu_runtime_home" \
        SMALLPHONEAI_COMPONENT_REPO_ROOT="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$ubuntu_repo_root}" \
        SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}" \
        SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}" \
        SMALLPHONEAI_COMPONENT_SOURCE_MODE="${SMALLPHONEAI_COMPONENT_SOURCE_MODE:-bundle}" \
        SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE="${SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE:-0}" \
        SMALLPHONEAI_COMPONENTS_AUTO_CLONE="${SMALLPHONEAI_COMPONENTS_AUTO_CLONE:-0}" \
        SMALLPHONEAI_COMPONENTS_STRICT="${SMALLPHONEAI_COMPONENTS_STRICT:-1}" \
        SMALLPHONEAI_COMPONENT_TARGETS="$ubuntu_targets" \
        OPENHOUSE_PI_RUNTIME="${OPENHOUSE_PI_RUNTIME:-${SMALLPHONEAI_PI_RUNTIME:-termux}}" \
        SMALLPHONEAI_PI_RUNTIME="${SMALLPHONEAI_PI_RUNTIME:-${OPENHOUSE_PI_RUNTIME:-termux}}" \
        SMALLPHONEAI_FORCE_PAYLOAD_REFRESH="${SMALLPHONEAI_FORCE_PAYLOAD_REFRESH:-0}" \
        SMALLPHONEAI_SERVICE_MANAGER_DIR="" \
        SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH="$native_config" \
        SMALLPHONEAI_REQUIRE_EXTERNAL_SERVICE_MANAGER=1 \
        SMALLPHONEAI_CC_CONNECT_DIR="${SMALLPHONEAI_CC_CONNECT_DIR:-}" \
        SMALLPHONEAI_SMALLPHONE_DIR="${SMALLPHONEAI_SMALLPHONE_DIR:-}" \
        SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-http://${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}}" \
        SMALLPHONEAI_SERVICE_MANAGER_TOKEN_FILE="$ubuntu_token_file" \
        bash -s < "$0"
    ); then
      exit 0
    else
      exit $?
    fi
  fi
  warn "Ubuntu 尚不可用，将在当前 Termux 环境尝试运行组件入口。"
fi

export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"

service_manager_shim_dir=""
cleanup_service_manager_shim() {
  if [ -n "$service_manager_shim_dir" ]; then
    rm -rf "$service_manager_shim_dir" >/dev/null 2>&1 || true
  fi
}

ensure_service_manager_token_shim() {
  local token shim
  if command -v service-manager >/dev/null 2>&1; then
    return 0
  fi
  token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  [ -n "$token" ] || return 1
  service_manager_shim_dir="$(mktemp -d "$TMPDIR/smallphoneai-sm-shim.XXXXXX")"
  shim="$service_manager_shim_dir/service-manager"
  cat > "$shim" <<'SH'
#!/usr/bin/env sh
set -eu
if [ "${1:-}" = "token" ] && [ "${2:-}" = "show" ]; then
  printf '%s\n' "${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  exit 0
fi
printf '%s\n' "service-manager shim only supports: token show" >&2
exit 2
SH
  chmod +x "$shim"
  export PATH="$service_manager_shim_dir:$PATH"
  trap cleanup_service_manager_shim EXIT INT HUP TERM
}

if is_current_ubuntu && [ "${SMALLPHONEAI_REQUIRE_EXTERNAL_SERVICE_MANAGER:-1}" = "1" ]; then
  ensure_service_manager_token_shim || true
fi

repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$HOME/smallphoneai-repos}"
payload_root="${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-${SMALLPHONEAI_PAYLOAD_ROOT:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}}"
component_source_mode="${SMALLPHONEAI_COMPONENT_SOURCE_MODE:-bundle}"
allow_git_update="${SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE:-${SMALLPHONEAI_COMPONENTS_AUTO_CLONE:-0}}"
strict="${SMALLPHONEAI_COMPONENTS_STRICT:-1}"
component_targets="${SMALLPHONEAI_COMPONENT_TARGETS:-}"
force_payload_refresh="${SMALLPHONEAI_FORCE_PAYLOAD_REFRESH:-0}"
failures=0
openhouse_home="${OPENHOUSEAI_HOME:-$HOME/.config/openhouseai}"
openhouse_subjects_dir="${OPENHOUSEAI_SUBJECTS_DIR:-$openhouse_home/subjects.d}"
openhouse_schema_dir="${OPENHOUSEAI_SCHEMA_DIR:-$openhouse_home/schemas}"
openhouse_system_dir="${OPENHOUSEAI_SYSTEM_DIR:-$openhouse_home/system}"

install_openhouse_system_cli() {
  local source="$bootstrap_root/scripts/openhouse-system"
  local install_dir
  local target

  if [ ! -f "$source" ]; then
    warn "openhouse-system CLI source not found: $source"
    return 1
  fi

  if is_termux; then
    install_dir="${PREFIX:-/data/data/com.termux/files/usr}/bin"
  else
    install_dir="$HOME/.local/bin"
  fi
  target="$install_dir/openhouse-system"
  mkdir -p "$install_dir"
  cp "$source" "$target"
  chmod 755 "$target"
  log "已部署 openhouse-system CLI：$target"
}

install_openhouse_system_schema() {
  local source="$bootstrap_root/schemas/subject.schema.json"
  local target="$openhouse_schema_dir/subject.schema.json"
  mkdir -p "$openhouse_schema_dir"
  if [ -f "$source" ]; then
    cp "$source" "$target"
  else
    cat > "$target" <<'JSON'
{"$schema":"https://json-schema.org/draft/2020-12/schema","title":"OpenHouse subject card","type":"object","required":["id","title","kind","summary"],"properties":{"id":{"type":"string"},"title":{"type":"string"},"kind":{"type":"string"},"summary":{"type":"string"},"serviceRefs":{"type":"array","items":{"oneOf":[{"type":"string"},{"type":"object","required":["id"],"properties":{"id":{"type":"string"},"runtime":{"type":"string"},"manager":{"type":"string"}}}]}},"entries":{"type":"array"},"locations":{"type":"array"},"ai":{"type":"object"},"checks":{"type":"object"}}}
JSON
  fi
  log "已部署 OpenHouse 主体 schema：$target"
}

install_default_subject_file() {
  local name="$1"
  local target="$openhouse_subjects_dir/$name"
  if [ -f "$target" ]; then
    return 0
  fi
  mkdir -p "$openhouse_subjects_dir"
  cat > "$target"
  log "已写入默认主体名片：$target"
}

install_default_subjects() {
  local source_dir="$bootstrap_root/subjects.d"
  local file
  mkdir -p "$openhouse_subjects_dir"
  if [ -d "$source_dir" ]; then
    for file in "$source_dir"/*.json; do
      [ -f "$file" ] || continue
      if [ ! -f "$openhouse_subjects_dir/$(basename "$file")" ]; then
        cp "$file" "$openhouse_subjects_dir/$(basename "$file")"
        log "已部署默认主体名片：$openhouse_subjects_dir/$(basename "$file")"
      fi
    done
    return 0
  fi

  install_default_subject_file "pi-agent.json" <<'JSON'
{"id":"pi-agent","title":"Pi Agent","kind":"runtime-http","summary":"OpenHouse main AI workbench and human-facing web entry.","serviceRefs":[{"id":"pi-agent","runtime":"termux","manager":"service-manager","home":"/data/data/com.termux/files/home","workingDirectory":"$HOME/smallphoneai-repos/pi-agent","workdir":"$HOME/smallphoneai-repos/pi-agent","command":"openhouse-pi-agent-sentinel","entryCommand":"openhouse-pi-agent-sentinel"},{"id":"pi-web","runtime":"termux","manager":"service-manager","home":"/data/data/com.termux/files/home","workingDirectory":"$HOME/smallphoneai-repos/pi-web","workdir":"$HOME/smallphoneai-repos/pi-web","command":"openhouse-pi-web-start","entryCommand":"openhouse-pi-web-start"}],"entries":[{"type":"web","label":"Pi Agent Web","url":"http://127.0.0.1:30141/"}],"locations":[{"runtime":"termux","path":"/data/data/com.termux/files/home/smallphoneai-repos/pi-agent","purpose":"Termux native pi-agent payload and npm package install source"},{"runtime":"termux","path":"/data/data/com.termux/files/home/smallphoneai-repos/pi-web","purpose":"Termux native pi-web payload install source"},{"runtime":"termux","path":"/data/data/com.termux/files/home/.pi","purpose":"Termux native Pi Agent data, extensions, and runtime state"},{"runtime":"ubuntu","user":"root","home":"/root","path":"$HOME/smallphoneai-repos/smallphone-active","workingDirectory":"$HOME/smallphoneai-repos/smallphone-active","workdir":"$HOME/smallphoneai-repos/smallphone-active","purpose":"Ubuntu AI workbench and compatibility/fallback repo; root is only the first-install default user, not the meaning of runtime=ubuntu"}],"ai":{"description":"Pi Agent is the primary OpenHouse AI workbench. Its service state is controlled by service-manager through the pi-web service id.","whenUnavailable":"First inspect service-manager status and logs for pi-agent/pi-web. If the services are running, run openhouse-system check pi-agent."},"checks":{"serviceTimeoutSeconds":5,"afterServiceOk":[{"type":"http","url":"http://127.0.0.1:30141/","timeoutSeconds":4},{"type":"pathExists","runtime":"termux","path":"/data/data/com.termux/files/home/smallphoneai-repos/pi-agent","timeoutSeconds":4},{"type":"pathExists","runtime":"termux","path":"/data/data/com.termux/files/home/smallphoneai-repos/pi-web","timeoutSeconds":4}]}}
JSON
  install_default_subject_file "file-inbox.json" <<'JSON'
{"id":"file-inbox","title":"File Inbox","kind":"file","summary":"Shared staging area for files opened with or shared to OpenHouse.","serviceRefs":[],"entries":[{"type":"file","label":"Android inbox","path":"/storage/emulated/0/OpenHouse/Inbox"}],"locations":[{"runtime":"android","path":"/storage/emulated/0/OpenHouse/Inbox","purpose":"user-visible Android storage"},{"runtime":"termux","path":"/data/data/com.termux/files/home/OpenHouse/Inbox","purpose":"Termux-native inbox projection"},{"runtime":"ubuntu","path":"/root/OpenHouse/Inbox","purpose":"Ubuntu/proot inbox projection"}],"ai":{"description":"Files from Android share/open-with flows should be staged here before AI processing.","whenUnavailable":"Check storage permission and projected inbox directories."},"checks":{"afterServiceOk":[{"type":"pathExists","runtime":"android","path":"/storage/emulated/0/OpenHouse/Inbox","timeoutSeconds":3}]}}
JSON
  install_default_subject_file "openhouse-workspace.json" <<'JSON'
{"id":"openhouse-workspace","title":"OpenHouse Workspace","kind":"file","summary":"Native work partition with Android, Termux, and Ubuntu visible roots.","serviceRefs":[],"entries":[{"type":"file","label":"Android workspace","path":"/storage/emulated/0/OpenHouse"}],"locations":[{"runtime":"android","path":"/storage/emulated/0/OpenHouse","purpose":"shared phone storage"},{"runtime":"termux","path":"/data/data/com.termux/files/home/OpenHouse","purpose":"Termux-native workspace"},{"runtime":"ubuntu","path":"/root/OpenHouse","purpose":"Ubuntu/proot workspace"}],"ai":{"description":"Use this workspace when explaining where OpenHouse data lives across Android, Termux, and Ubuntu.","whenUnavailable":"Check storage permission and root directory projections."},"checks":{"afterServiceOk":[{"type":"pathExists","runtime":"android","path":"/storage/emulated/0/OpenHouse","timeoutSeconds":3},{"type":"pathExists","runtime":"ubuntu","path":"/root/OpenHouse","timeoutSeconds":4}]}}
JSON
  install_default_subject_file "service-control.json" <<'JSON'
{"id":"service-control","title":"Service Control","kind":"runtime-http","summary":"Local service-manager control surface for service status, lifecycle actions, and logs.","serviceRefs":[],"entries":[{"type":"web","label":"service-manager","url":"http://127.0.0.1:20087/"}],"locations":[{"runtime":"termux","path":"/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json","purpose":"OpenHouse service-manager config and token"}],"ai":{"description":"service-manager is the standing service control layer. It answers service status, lifecycle, and logs for explicit service ids; it does not infer runtime or perform subject endpoint/file/skill checks.","whenUnavailable":"Repair the Termux native service-manager control plane before using higher-level subject checks."},"checks":{"afterServiceOk":[{"type":"http","url":"http://127.0.0.1:20087/api/v1/health","timeoutSeconds":3}]}}
JSON
}

write_ubuntu_openhouse_system_shim() {
  local target="${1:-/usr/local/bin/openhouse-system}"
  mkdir -p "$(dirname "$target")"
  cat > "$target" <<'SH'
#!/usr/bin/env sh
set -eu

TERMUX_HOME="${OPENHOUSEAI_TERMUX_HOME:-/data/data/com.termux/files/home}"
TERMUX_PREFIX="${OPENHOUSEAI_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
NATIVE_CLI="${OPENHOUSE_SYSTEM_NATIVE_CLI:-$TERMUX_PREFIX/bin/openhouse-system}"

if [ ! -f "$NATIVE_CLI" ]; then
  printf '%s\n' "openhouse-system native CLI not found: $NATIVE_CLI" >&2
  printf '%s\n' "Run this from Termux first: bash bootstrap.sh components" >&2
  exit 127
fi

export OPENHOUSE_SYSTEM_CALLER_RUNTIME="${OPENHOUSE_SYSTEM_CALLER_RUNTIME:-ubuntu}"
export HOME="$TERMUX_HOME"
export PREFIX="$TERMUX_PREFIX"
export OPENHOUSEAI_HOME="${OPENHOUSEAI_HOME:-$TERMUX_HOME/.config/openhouseai}"
export OPENHOUSEAI_SUBJECTS_DIR="${OPENHOUSEAI_SUBJECTS_DIR:-$OPENHOUSEAI_HOME/subjects.d}"
export OPENHOUSEAI_SYSTEM_DIR="${OPENHOUSEAI_SYSTEM_DIR:-$OPENHOUSEAI_HOME/system}"
export SERVICE_MANAGER_CONFIG_PATH="${SERVICE_MANAGER_CONFIG_PATH:-$OPENHOUSEAI_HOME/service-manager/config.json}"
export SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH="${SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH:-$SERVICE_MANAGER_CONFIG_PATH}"
export SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-http://127.0.0.1:20087}"
export PATH="$TERMUX_PREFIX/bin:/system/bin:/system/xbin:$PATH"
export LD_LIBRARY_PATH="$TERMUX_PREFIX/lib:${LD_LIBRARY_PATH:-}"

if [ -x "$TERMUX_PREFIX/bin/bash" ]; then
  exec "$TERMUX_PREFIX/bin/bash" "$NATIVE_CLI" --caller-runtime ubuntu "$@"
fi

exec /usr/bin/env bash "$NATIVE_CLI" --caller-runtime ubuntu "$@"
SH
  chmod 755 "$target"
}

install_ubuntu_openhouse_system_shim() {
  local termux_home="${OPENHOUSEAI_TERMUX_HOME:-/data/data/com.termux/files/home}"
  local termux_prefix="${OPENHOUSEAI_TERMUX_PREFIX:-${PREFIX:-/data/data/com.termux/files/usr}}"

  if is_current_ubuntu; then
    write_ubuntu_openhouse_system_shim "/usr/local/bin/openhouse-system"
    log "已部署 Ubuntu openhouse-system shim：/usr/local/bin/openhouse-system"
    return 0
  fi

  if is_termux && command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    log "正在 Ubuntu 内部署 openhouse-system shim。"
    proot-distro login ubuntu -- env \
      OPENHOUSEAI_TERMUX_HOME="$termux_home" \
      OPENHOUSEAI_TERMUX_PREFIX="$termux_prefix" \
      bash -s <<'SH'
set -euo pipefail
mkdir -p /usr/local/bin
cat > /usr/local/bin/openhouse-system <<'EOS'
#!/usr/bin/env sh
set -eu

TERMUX_HOME="${OPENHOUSEAI_TERMUX_HOME:-/data/data/com.termux/files/home}"
TERMUX_PREFIX="${OPENHOUSEAI_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
NATIVE_CLI="${OPENHOUSE_SYSTEM_NATIVE_CLI:-$TERMUX_PREFIX/bin/openhouse-system}"

if [ ! -f "$NATIVE_CLI" ]; then
  printf '%s\n' "openhouse-system native CLI not found: $NATIVE_CLI" >&2
  printf '%s\n' "Run this from Termux first: bash bootstrap.sh components" >&2
  exit 127
fi

export OPENHOUSE_SYSTEM_CALLER_RUNTIME="${OPENHOUSE_SYSTEM_CALLER_RUNTIME:-ubuntu}"
export HOME="$TERMUX_HOME"
export PREFIX="$TERMUX_PREFIX"
export OPENHOUSEAI_HOME="${OPENHOUSEAI_HOME:-$TERMUX_HOME/.config/openhouseai}"
export OPENHOUSEAI_SUBJECTS_DIR="${OPENHOUSEAI_SUBJECTS_DIR:-$OPENHOUSEAI_HOME/subjects.d}"
export OPENHOUSEAI_SYSTEM_DIR="${OPENHOUSEAI_SYSTEM_DIR:-$OPENHOUSEAI_HOME/system}"
export SERVICE_MANAGER_CONFIG_PATH="${SERVICE_MANAGER_CONFIG_PATH:-$OPENHOUSEAI_HOME/service-manager/config.json}"
export SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH="${SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH:-$SERVICE_MANAGER_CONFIG_PATH}"
export SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-http://127.0.0.1:20087}"
export PATH="$TERMUX_PREFIX/bin:/system/bin:/system/xbin:$PATH"
export LD_LIBRARY_PATH="$TERMUX_PREFIX/lib:${LD_LIBRARY_PATH:-}"

if [ -x "$TERMUX_PREFIX/bin/bash" ]; then
  exec "$TERMUX_PREFIX/bin/bash" "$NATIVE_CLI" --caller-runtime ubuntu "$@"
fi

exec /usr/bin/env bash "$NATIVE_CLI" --caller-runtime ubuntu "$@"
EOS
chmod 755 /usr/local/bin/openhouse-system
SH
    return 0
  fi

  return 0
}

render_openhouse_system_index() {
  mkdir -p "$openhouse_system_dir"
  if ! command -v openhouse-system >/dev/null 2>&1; then
    warn "openhouse-system 不在 PATH，跳过系统目录渲染。"
    return 1
  fi
  if ! command -v jq >/dev/null 2>&1; then
    warn "jq 不可用，跳过系统目录渲染。请安装：pkg install jq"
    return 1
  fi
  OPENHOUSEAI_HOME="$openhouse_home" \
    OPENHOUSEAI_SUBJECTS_DIR="$openhouse_subjects_dir" \
    OPENHOUSEAI_SYSTEM_DIR="$openhouse_system_dir" \
    openhouse-system render
}

install_openhouse_system() {
  if is_current_ubuntu; then
    install_ubuntu_openhouse_system_shim
    return 0
  fi

  install_openhouse_system_cli || return 1
  install_openhouse_system_schema
  install_default_subjects
  render_openhouse_system_index || warn "OpenHouse 主系统目录暂未渲染成功；可稍后执行：openhouse-system render"
  install_ubuntu_openhouse_system_shim || warn "Ubuntu openhouse-system shim 部署未完成；可稍后从 Termux 重新运行：bash bootstrap.sh components"
}

normalize_target() {
  case "$1" in
    openhouse-system|main-system|system)
      printf 'openhouse-system'
      ;;
    openhouse-connect)
      printf 'cc-connect'
      ;;
    github|github-helper|github-config)
      printf 'github-config-helper'
      ;;
    hermes-agent|hermes-webui)
      printf 'hermes'
      ;;
    openhouse-web|web-shell|openhouse-shell)
      printf 'openhouse-web'
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
      openhouse-system|service-manager|openhouse-web|pi-agent|pi-web|wuyou|github-config-helper|cc-connect|smallphone|hermes)
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
  list_file="$(mktemp "$TMPDIR/smallphoneai-payload-list.XXXXXX")"
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
      component_binary_current_env_executable "$payload_name" "$source/service-manager" \
        || component_binary_current_env_executable "$payload_name" "$source/target/release/service-manager"
      ;;
    openhouse-connect)
      component_binary_current_env_executable "$payload_name" "$source/cc-connect"
      ;;
    wuyou)
      component_binary_current_env_executable "$payload_name" "$source/wuyou"
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
    wuyou)
      printf 'wuyou'
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
    wuyou)
      printf '^wuyou$'
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
    wuyou)
      printf 'wuyou at wuyou'
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
    service-manager)
      [ -x "$source/service-manager" ] \
        && [ "$("$source/service-manager" --version 2>/dev/null | tr -d '\r\n')" = "service-manager 0.3.0" ] \
        || return 0
      ;;
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
      [ -f "$source/scripts/install.sh" ] && grep -Fq 'Dependency installation is disabled' "$source/scripts/install.sh" \
        || return 0
      ;;
    openhouse-web)
      [ -f "$source/src/server.mjs" ] \
        && [ -f "$source/src/auth.mjs" ] \
        && [ -f "$source/config/openhouse-web.service.json" ] \
        && grep -Fq 'residentByDefault' "$source/config/openhouse-web.service.json" \
        && grep -Fq 'issueTicket(now = Date.now())' "$source/src/auth.mjs" \
        || return 0
      ;;
    hermes)
      [ -f "$source/openhouse/register-service.sh" ] \
        && grep -Fq '/api/v1/registry/apply' "$source/openhouse/register-service.sh" \
        && [ -f "$source/openhouse/component-manifest.json" ] \
        && [ -f "$source/openhouse/openhouse.ai.md" ] \
        && [ -f "$source/openhouse/capabilities.json" ] \
        || return 0
      ;;
    pi-agent)
      [ -f "$source/scripts/register-service.sh" ] || return 0
      grep -Fq '"provider": "termux-process"' "$source/scripts/register-service.sh" \
        && grep -Fq '"strategy": "termux-process"' "$source/scripts/register-service.sh" \
        && grep -Fq 'atomic_install_json' "$source/scripts/register-service.sh" \
        && grep -Fq 'make_tmp_dir' "$source/scripts/register-service.sh" \
        && grep -Fq 'fallback_upsert_service' "$source/scripts/register-service.sh" \
        && grep -Fq '/api/v1/services/$SERVICE_NAME/register' "$source/scripts/register-service.sh" \
        && grep -Fq 'refused to create random id' "$source/scripts/register-service.sh" \
        && ! grep -Fq 'POST "/api/v1/services" "$SPEC_PATH"' "$source/scripts/register-service.sh" \
        && grep -Fq 'child=\$!' "$source/scripts/register-service.sh" \
        && ! grep -Fq '${TMPDIR:-/tmp}' "$source/scripts/register-service.sh" \
        || return 0
      if [ "$(openhouse_pi_runtime)" = "termux" ] && is_termux; then
        [ -f "$source/scripts/install.sh" ] \
          && grep -Fq 'patch_termux_node_entrypoints()' "$source/scripts/install.sh" \
          || return 0
        [ -f "$source/bin/openhouse-pi-agent-sentinel" ] \
          && sed -n '1p' "$source/bin/openhouse-pi-agent-sentinel" | grep -Fq '/data/data/com.termux/files/usr/bin/env sh' \
          || return 0
      fi
      ;;
    pi-web)
      [ -f "$source/scripts/register-service.sh" ] || return 0
      grep -Fq '"provider": "termux-process"' "$source/scripts/register-service.sh" \
        && grep -Fq '"strategy": "termux-process"' "$source/scripts/register-service.sh" \
        && grep -Fq 'atomic_install_json' "$source/scripts/register-service.sh" \
        && grep -Fq 'make_tmp_dir' "$source/scripts/register-service.sh" \
        && grep -Fq 'fallback_upsert_service' "$source/scripts/register-service.sh" \
        && grep -Fq '/api/v1/services/$SERVICE_NAME/register' "$source/scripts/register-service.sh" \
        && grep -Fq 'refused to create random id' "$source/scripts/register-service.sh" \
        && ! grep -Fq 'POST "/api/v1/services" "$SPEC_PATH"' "$source/scripts/register-service.sh" \
        && grep -Fq 'child=\$!' "$source/scripts/register-service.sh" \
        && grep -Fq 'pi-web --host' "$source/scripts/register-service.sh" \
        && ! grep -Fq '${TMPDIR:-/tmp}' "$source/scripts/register-service.sh" \
        || return 0
      if [ "$(openhouse_pi_runtime)" = "termux" ] && is_termux; then
        [ -f "$source/bin/openhouse-pi-web-start" ] \
          && sed -n '1p' "$source/bin/openhouse-pi-web-start" | grep -Fq '/data/data/com.termux/files/usr/bin/env sh' \
          && grep -Fq 'PI_WEB_HOST="${PI_WEB_HOST:-127.0.0.1}"' "$source/bin/openhouse-pi-web-start" \
          && [ -f "$source/bin/pi-web" ] \
          && sed -n '1p' "$source/bin/pi-web" | grep -Fq '/data/data/com.termux/files/usr/bin/env sh' \
          && grep -Fq 'Usage:' "$source/bin/pi-web" \
          || return 0
      fi
      ;;
    wuyou)
      [ -f "$source/scripts/install.sh" ] \
        && grep -Fq 'GLOBAL_BIN' "$source/scripts/install.sh" \
        && [ -f "$source/scripts/check.sh" ] \
        && grep -Fq 'wuyou --help' "$source/scripts/check.sh" \
        && [ -f "$source/wuyou" ] \
        || return 0
      ;;
  esac

  return 1
}

sed_escape_replacement() {
  printf '%s' "$1" | sed 's/[\/&]/\\&/g'
}

patch_termux_script_shebang() {
  local file="$1"
  local interpreter="$2"
  local termux_prefix="$3"

  [ -f "$file" ] || return 0
  case "$(sed -n '1p' "$file" 2>/dev/null || true)" in
    '#!'*)
      sed -i "1s|^#!.*|#!$termux_prefix/bin/env $interpreter|" "$file" || true
      ;;
  esac
}

patch_termux_pi_agent_install_script() {
  local file="$1"
  local tmp

  [ -f "$file" ] || return 0
  if ! grep -Fq 'patch_termux_node_entrypoints()' "$file"; then
    tmp="$file.tmp.$$"
    awk '
      /^main\(\) \{/ && inserted == 0 {
        print ""
        print "patch_termux_node_entrypoints() {"
        print "  termux_env=\"${PREFIX:-/data/data/com.termux/files/usr}/bin/env\""
        print "  [ -x \"$termux_env\" ] || return 0"
        print "  for entry in \"$GLOBAL_PREFIX/bin/pi\" \"$GLOBAL_PREFIX/bin/pi-ai\"; do"
        print "    [ -e \"$entry\" ] || continue"
        print "    target=\"$(readlink -f \"$entry\" 2>/dev/null || true)\""
        print "    [ -n \"$target\" ] || target=\"$entry\""
        print "    [ -f \"$target\" ] || continue"
        print "    sed -n \"1p\" \"$target\" | grep -Eq \"^#!/usr/bin/env node|^#!.* node\" || continue"
        print "    sed -i \"1s|^#!.*|#!$termux_env node|\" \"$target\" || true"
        print "  done"
        print "}"
        print ""
        inserted = 1
      }
      { print }
    ' "$file" > "$tmp" && mv "$tmp" "$file"
    chmod +x "$file" || true
  fi

  if ! grep -Eq '^[[:space:]]*patch_termux_node_entrypoints[[:space:]]*$' "$file"; then
    sed -i '/^[[:space:]]*install_pi_cli[[:space:]]*$/a\  patch_termux_node_entrypoints' "$file" || true
  fi
}

patch_pi_payload_for_termux() {
  local payload_name="$1"
  local dir="$2"
  local termux_home termux_prefix escaped_home file interpreter

  [ "$(openhouse_pi_runtime)" = "termux" ] || return 0
  is_termux || return 0
  case "$payload_name" in
    pi-agent|pi-web)
      ;;
    *)
      return 0
      ;;
  esac

  termux_home="${OPENHOUSEAI_TERMUX_HOME:-$HOME}"
  termux_prefix="${OPENHOUSEAI_TERMUX_PREFIX:-${PREFIX:-/data/data/com.termux/files/usr}}"
  escaped_home="$(sed_escape_replacement "$termux_home")"
  log "$payload_name: 正在适配 APK payload 到 Termux native 路径：$termux_home"

  for file in \
    "$dir/scripts/install.sh" \
    "$dir/scripts/check.sh" \
    "$dir/scripts/register-service.sh" \
    "$dir/bin/openhouse-pi-agent-sentinel" \
    "$dir/bin/openhouse-pi-web-start" \
    "$dir/bin/pi-web"; do
    [ -f "$file" ] || continue
    sed -i "s#/root#$escaped_home#g" "$file"
    sed -i "s#/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin:${termux_prefix}/bin#${termux_prefix}/bin:/system/bin:/system/xbin#g" "$file" || true
    sed -i "s#/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin:${termux_prefix}/bin#${termux_prefix}/bin:/system/bin:/system/xbin#g" "$file" || true
    sed -i "s#/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin#${termux_prefix}/bin:/system/bin:/system/xbin#g" "$file" || true
    case "$file" in
      */scripts/*.sh)
        interpreter="bash"
        ;;
      *)
        interpreter="sh"
        ;;
    esac
    patch_termux_script_shebang "$file" "$interpreter" "$termux_prefix"
  done

  if [ "$payload_name" = "pi-agent" ]; then
    patch_termux_pi_agent_install_script "$dir/scripts/install.sh"
  fi
}

ensure_termux_node_for_pi() {
  local major
  [ "$(openhouse_pi_runtime)" = "termux" ] || return 0
  is_termux || return 0
  if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    warn "pi-agent/pi-web 默认运行在 Termux native，但 node/npm 不可用；请先运行：bash bootstrap.sh termux-node"
    return 1
  fi
  major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || printf 0)"
  case "$major" in
    ''|*[!0-9]*)
      major=0
      ;;
  esac
  if [ "$major" -lt 24 ]; then
    warn "pi-agent/pi-web 需要 Termux Node.js 24 LTS（major >= 24），当前为 $(node -v 2>/dev/null || printf unknown)；请先运行：bash bootstrap.sh termux-node"
    return 1
  fi
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
    if [ "$payload_name" = "hermes" ]; then
      if [ ! -f "$source/openhouse/install.sh" ] \
        || [ ! -f "$source/openhouse/check.sh" ] \
        || [ ! -f "$source/openhouse/register-service.sh" ]; then
        warn "$name: APK payload directory is missing openhouse/install.sh, openhouse/check.sh, or openhouse/register-service.sh: $source"
        return 1
      fi
      return 0
    fi
    if [ ! -f "$source/scripts/install.sh" ] || [ ! -f "$source/scripts/check.sh" ]; then
      warn "$name: APK payload directory is missing scripts/install.sh or scripts/check.sh: $source"
      return 1
    fi
    if required_payload_executable "$payload_name" >/dev/null; then
      required_description="$(required_payload_executable_description "$payload_name")"
      if ! payload_dir_contains_executable "$source" "$payload_name"; then
        warn "$name: APK payload directory must contain current-environment executable $required_description: $source"
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
    if [ "$payload_name" = "hermes" ]; then
      if ! payload_archive_contains "$source" '(^|/)openhouse/install\.sh$' \
        || ! payload_archive_contains "$source" '(^|/)openhouse/check\.sh$' \
        || ! payload_archive_contains "$source" '(^|/)openhouse/register-service\.sh$' \
        || ! payload_archive_contains "$source" '(^|/)openhouse/component-manifest\.json$' \
        || ! payload_archive_contains "$source" '(^|/)openhouse/openhouse\.ai\.md$' \
        || ! payload_archive_contains "$source" '(^|/)openhouse/capabilities\.json$'; then
        warn "$name: APK payload archive must contain Hermes openhouse integration files: $source"
        return 1
      fi
      return 0
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

  work_dir="$(mktemp -d "$TMPDIR/smallphoneai-payload.XXXXXX")"
  tar -"$tar_extract_flags" "$source" -C "$work_dir"

  if [ -f "$work_dir/scripts/install.sh" ] || [ -f "$work_dir/openhouse/install.sh" ]; then
    payload_dir="$work_dir"
  else
    payload_dir="$(find "$work_dir" -mindepth 2 -maxdepth 4 \( -path '*/scripts/install.sh' -o -path '*/openhouse/install.sh' \) -type f -print | sed -e 's#/scripts/install\.sh$##' -e 's#/openhouse/install\.sh$##' | head -n 1)"
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
      log "$name: 已存在安装目录但缺少或无法运行 APK bundle 必需二进制，将从 payload 刷新：$dir"
    elif payload_dir_needs_refresh "$dir" "$payload_name"; then
      log "$name: 已存在安装目录但组件脚本不是当前 APK bundle 合同，将从 payload 刷新：$dir"
    else
      log "$name: 已存在安装目录，已验证 APK payload 可用：$dir"
      return 0
    fi
  fi

  log "$name: 从 APK-bundled payload 安装到 $dir（source: $source）"
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
        if component_binary_current_env_executable "$payload_name" "$candidate"; then
          printf '%s\n' "$candidate"
          return 0
        fi
      done
      ;;
    openhouse-connect)
      candidate="$dir/cc-connect"
      if component_binary_current_env_executable "$payload_name" "$candidate"; then
        printf '%s\n' "$candidate"
        return 0
      fi
      ;;
    wuyou)
      candidate="$dir/wuyou"
      if component_binary_current_env_executable "$payload_name" "$candidate"; then
        printf '%s\n' "$candidate"
        return 0
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
    warn "$name: bundle 模式要求安装目录包含当前环境可执行的 $required_binary；拒绝运行 scripts/install.sh 以避免 GitHub/npm/go fallback：$dir"
    return 1
  fi

  log "$name: bundle/local 安装将使用 payload 内可执行文件：$local_binary"
}

run_repo_script_command() {
  local payload_name="$1"
  local dir="$2"
  local script="$3"
  local local_binary
  local sm_config_path
  local sm_install_service
  local sm_token

  case "$component_source_mode:$payload_name:$script" in
    bundle:service-manager:scripts/install.sh)
      local_binary="$(local_component_binary_path "$payload_name" "$dir")"
      sm_config_path="${SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH:-${SERVICE_MANAGER_CONFIG_PATH:-$HOME/.config/openhouseai/service-manager/config.json}}"
      if [ -n "${SMALLPHONEAI_SERVICE_MANAGER_INSTALL_SERVICE:-}" ]; then
        sm_install_service="${SMALLPHONEAI_SERVICE_MANAGER_INSTALL_SERVICE}"
      elif is_termux; then
        sm_install_service="1"
      else
        sm_install_service="0"
      fi
      log "service-manager: 使用 local payload binary 安装：$local_binary"
      log "service-manager: 安装目标 PATH 将由子仓库 install.sh 处理，配置文件：$sm_config_path"
      run_logged env \
        SMALLPHONEAI_OFFLINE_INSTALL=1 \
        SMALLPHONEAI_LOCAL_INSTALL=1 \
        CONFIG_PATH="$sm_config_path" \
        BIND="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}" \
        INSTALL_SERVICE="$sm_install_service" \
        SERVICE_MANAGER_INSTALL_MODE=local \
        bash "./$script" "$local_binary"
      ;;
    bundle:openhouse-connect:scripts/install.sh)
      run_logged env \
        SMALLPHONEAI_OFFLINE_INSTALL=1 \
        SMALLPHONEAI_LOCAL_INSTALL=1 \
        CC_CONNECT_INSTALL_MODE=local \
        CC_CONNECT_LOCAL_INSTALL=1 \
        CC_CONNECT_OFFLINE_INSTALL=1 \
        bash "./$script"
      ;;
    bundle:smallphone:scripts/install.sh)
      run_logged env \
        SMALLPHONEAI_OFFLINE_INSTALL=1 \
        SMALLPHONEAI_LOCAL_INSTALL=1 \
        SMALLPHONE_SKIP_DEP_INSTALL=1 \
        bash "./$script"
      ;;
    bundle:hermes:openhouse/install.sh)
      run_logged env \
        SMALLPHONEAI_OFFLINE_INSTALL=1 \
        SMALLPHONEAI_LOCAL_INSTALL=1 \
        bash "./$script"
      ;;
    bundle:pi-agent:scripts/register-service.sh|bundle:pi-web:scripts/register-service.sh)
      sm_token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
      if [ -z "$sm_token" ] && is_termux; then
        sm_token="$(resolve_termux_service_manager_token || true)"
      fi
      run_with_service_manager_auth "$sm_token" run_logged env \
        SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-http://${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}}" \
        bash "./$script"
      ;;
    *)
      run_logged bash "./$script"
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
  patch_pi_payload_for_termux "$payload_name" "$dir"

  if ! validate_bundle_local_install_source "$name" "$payload_name" "$dir"; then
    if [ "$required" = "1" ]; then
      failures=$((failures + 1))
    fi
    return 0
  fi

  if [ "$payload_name" = "hermes" ]; then
    run_repo_script "$name" "$dir" "openhouse/install.sh" "$required" "$payload_name"
    run_repo_script "$name" "$dir" "openhouse/check.sh" "$required" "$payload_name"
    run_repo_script "$name" "$dir" "openhouse/register-service.sh" "0" "$payload_name"
    return 0
  fi

  run_repo_script "$name" "$dir" "scripts/install.sh" "$required" "$payload_name"
  run_repo_script "$name" "$dir" "scripts/check.sh" "$required" "$payload_name"
  run_repo_script "$name" "$dir" "scripts/register-service.sh" "0" "$payload_name"
}

service_manager_dir="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-$(default_path service-manager)}"
openhouse_web_dir="${OPENHOUSE_WEB_DIR:-${SMALLPHONEAI_OPENHOUSE_WEB_DIR:-$(default_path openhouse-web)}}"
pi_agent_dir="${OPENHOUSE_PI_AGENT_DIR:-${SMALLPHONEAI_PI_AGENT_DIR:-$(default_path pi-agent)}}"
pi_web_dir="${OPENHOUSE_PI_WEB_DIR:-${SMALLPHONEAI_PI_WEB_DIR:-$(default_path pi-web)}}"
wuyou_dir="${OPENHOUSE_WUYOU_DIR:-${SMALLPHONEAI_WUYOU_DIR:-$(default_path wuyou)}}"
github_config_helper_dir="${OPENHOUSE_GITHUB_CONFIG_HELPER_DIR:-${SMALLPHONEAI_GITHUB_CONFIG_HELPER_DIR:-$(default_path github-config-helper)}}"
cc_connect_dir="${SMALLPHONEAI_CC_CONNECT_DIR:-$(default_path openhouse-connect)}"
smallphone_dir="${SMALLPHONEAI_SMALLPHONE_DIR:-$(default_path smallphone-active)}"
hermes_dir="${SMALLPHONEAI_HERMES_DIR:-$(default_path hermes)}"

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
  log "本次处理默认组件：openhouse-system、service-manager、openhouse-web、pi-agent、pi-web、wuyou、github-config-helper、cc-connect/openhouse-connect、SmallPhone、Hermes。"
fi

install_openhouse_system || warn "OpenHouse 主系统 CLI/主体名片部署未完成。"

validate_component_targets

if should_run_component "service-manager"; then
  run_component "service-manager" "$service_manager_dir" "${SMALLPHONEAI_SERVICE_MANAGER_GIT_URL:-https://github.com/jiwuyou/service-manager.git}" "1" "service-manager"
fi
if should_run_component "openhouse-web"; then
  if ensure_termux_node_for_pi; then
    run_component "OpenHouse Web" "$openhouse_web_dir" "${OPENHOUSE_WEB_GIT_URL:-}" "1" "openhouse-web"
  else
    failures=$((failures + 1))
  fi
fi
if should_run_component "pi-agent"; then
  if ensure_termux_node_for_pi; then
    run_component "pi-agent" "$pi_agent_dir" "${OPENHOUSE_PI_AGENT_GIT_URL:-}" "1" "pi-agent"
  else
    failures=$((failures + 1))
  fi
fi
if should_run_component "pi-web"; then
  if ensure_termux_node_for_pi; then
    run_component "pi-web" "$pi_web_dir" "${OPENHOUSE_PI_WEB_GIT_URL:-}" "1" "pi-web"
  else
    failures=$((failures + 1))
  fi
fi
if should_run_component "wuyou"; then
  run_component "wuyou" "$wuyou_dir" "${OPENHOUSE_WUYOU_GIT_URL:-}" "1" "wuyou"
fi
if should_run_component "github-config-helper"; then
  run_component "github-config-helper" "$github_config_helper_dir" "${OPENHOUSE_GITHUB_CONFIG_HELPER_GIT_URL:-}" "1" "github-config-helper"
fi
if should_run_component "cc-connect"; then
  run_component "cc-connect/openhouse-connect" "$cc_connect_dir" "${SMALLPHONEAI_CC_CONNECT_GIT_URL:-https://github.com/jiwuyou/openhouse-connect-fresh.git}" "1" "openhouse-connect"
fi
if should_run_component "smallphone"; then
  run_component "SmallPhone" "$smallphone_dir" "${SMALLPHONEAI_SMALLPHONE_GIT_URL:-https://github.com/jiwuyou/wuxian-smallphone.git}" "1" "smallphone"
fi
if should_run_component "hermes"; then
  run_component "Hermes" "$hermes_dir" "${SMALLPHONEAI_HERMES_GIT_URL:-}" "1" "hermes"
fi

if [ "$failures" -ne 0 ]; then
  if [ "$strict" = "1" ]; then
    warn "SmallPhoneAI 运行组件存在 $failures 个失败项。"
    exit 1
  fi
  warn "SmallPhoneAI 运行组件存在 $failures 个失败项；SMALLPHONEAI_COMPONENTS_STRICT=0，继续。"
fi

log "SmallPhoneAI 运行组件安装/检查/注册阶段完成。"
