#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
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

run_environment_probe() {
  local probe="${PREFIX:-/data/data/com.termux/files/usr}/bin/smallphoneai-env-probe"
  if [ -x "$probe" ]; then
    log "正在执行环境探测命令：$probe"
    run_logged "$probe" || true
  else
    log "环境探测命令不存在，使用内置探测逻辑。"
  fi
  log "当前运行环境：$(detect_smallphoneai_runtime)"
}

run_environment_probe

if ! is_termux; then
  log "Termux 准备阶段只能在 Termux 外层运行。当前运行环境：$(detect_smallphoneai_runtime)"
  exit 2
fi

TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_BIN_DIR="$TERMUX_PREFIX/bin"
ENV_PROBE_COMMAND="$TERMUX_BIN_DIR/smallphoneai-env-probe"
BROWSER_COMMAND="$TERMUX_BIN_DIR/openhouse-browser"
UBUNTU_BROWSER_COMMAND="$TERMUX_PREFIX/var/lib/proot-distro/containers/ubuntu/rootfs/usr/local/bin/openhouse-browser"
DOC_DIR="$TERMUX_HOME/openhouseai-docs"
LEGACY_DOC_DIR="$TERMUX_HOME/smallphoneai-docs"
OPENHOUSE_HOME_DIR="$TERMUX_HOME/openhouse"
WORKSPACE_DIR="$OPENHOUSE_HOME_DIR/workspace"
LEGACY_WORKSPACE_DIR="$TERMUX_HOME/workspace"
ANDROID_SHARED_OPENHOUSE_DIR="$TERMUX_HOME/storage/shared/OpenHouse"
TERMUX_CONFIG_DIR="$TERMUX_HOME/.termux"
TERMUX_PROPERTIES_FILE="$TERMUX_CONFIG_DIR/termux.properties"

install_env_probe_cli() {
  if [ -x "$ENV_PROBE_COMMAND" ]; then
    log "环境探测 CLI 已存在：$ENV_PROBE_COMMAND"
    return 0
  fi

  mkdir -p "$TERMUX_BIN_DIR"
  cat > "$ENV_PROBE_COMMAND" <<'EOF'
#!/data/data/com.termux/files/usr/bin/env bash
set -euo pipefail

INSTALL_SIDE="termux"

detect_runtime() {
  if [ -r /etc/os-release ] && grep -qi 'ubuntu' /etc/os-release; then
    printf 'ubuntu'
    return 0
  fi

  if [ -n "${TERMUX_VERSION:-}" ] || [ "${PREFIX:-}" = "/data/data/com.termux/files/usr" ]; then
    printf 'termux'
    return 0
  fi

  printf 'unknown'
}

detect_ubuntu_rootfs() {
  case "$(detect_runtime)" in
    ubuntu)
      printf 'installed'
      ;;
    termux)
      if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
        printf 'installed'
      else
        printf 'missing'
      fi
      ;;
    *)
      printf 'unknown'
      ;;
  esac
}

main() {
  printf 'SMALLPHONEAI_INSTALL_SIDE=%s\n' "$INSTALL_SIDE"
  printf 'SMALLPHONEAI_RUNTIME=%s\n' "$(detect_runtime)"
  printf 'SMALLPHONEAI_UBUNTU_ROOTFS=%s\n' "$(detect_ubuntu_rootfs)"
}

main "$@"
EOF
  chmod 755 "$ENV_PROBE_COMMAND"
  log "已注入环境探测 CLI：$ENV_PROBE_COMMAND"
}

install_controlled_browser_cli() {
  mkdir -p "$TERMUX_BIN_DIR" "$TERMUX_HOME/.openhouse-browser/requests" "$TERMUX_HOME/.openhouse-browser/results"
  chmod 700 "$TERMUX_HOME/.openhouse-browser" "$TERMUX_HOME/.openhouse-browser/requests" "$TERMUX_HOME/.openhouse-browser/results" || true

  if { [ -e "$BROWSER_COMMAND" ] || [ -L "$BROWSER_COMMAND" ]; } && ! grep -q 'OPENHOUSE_BROWSER_MANAGED=1' "$BROWSER_COMMAND" 2>/dev/null; then
    log "openhouse-browser 已存在且不是本安装脚本管理的文件，跳过：$BROWSER_COMMAND"
    return 0
  fi

  local tmp_command="${BROWSER_COMMAND}.tmp.$$"
  cat > "$tmp_command" <<'EOF'
#!/data/data/com.termux/files/usr/bin/env bash
# OPENHOUSE_BROWSER_MANAGED=1
set -euo pipefail

readonly TARGET_ACTIVITY="com.termux/.app.activities.OpenHouseHomeActivity"
readonly TARGET_RECEIVER="com.termux/.app.browser.ControlledBrowserCommandReceiver"
readonly COMMAND_ACTION="com.termux.app.browser.action.CONTROLLED_BROWSER_COMMAND"
readonly PAGE_EXTRA="com.termux.openhouse.PAGE"
readonly COMMAND_EXTRA="com.termux.app.browser.extra.COMMAND"
readonly URL_EXTRA="com.termux.app.browser.extra.URL"
readonly TAB_EXTRA="com.termux.app.browser.extra.TAB_ID"
readonly TAB_INDEX_EXTRA="com.termux.app.browser.extra.TAB_INDEX"
readonly REQUEST_ID_EXTRA="com.termux.app.browser.extra.REQUEST_ID"
readonly REQUEST_FILE_EXTRA="com.termux.app.browser.extra.REQUEST_FILE"
readonly RESULT_FILE_EXTRA="com.termux.app.browser.extra.RESULT_FILE"
readonly TIMEOUT_MS_EXTRA="com.termux.app.browser.extra.TIMEOUT_MS"
readonly TOKEN_EXTRA="com.termux.app.browser.extra.TOKEN"
readonly LEGACY_COMMAND_EXTRA="com.termux.openhouse.browser.COMMAND"
readonly LEGACY_URL_EXTRA="com.termux.openhouse.browser.URL"
readonly LEGACY_TAB_EXTRA="com.termux.openhouse.browser.TAB"
readonly LEGACY_REQUEST_ID_EXTRA="com.termux.openhouse.browser.REQUEST_ID"
readonly LEGACY_REQUEST_FILE_EXTRA="com.termux.openhouse.browser.REQUEST_FILE"
readonly LEGACY_RESULT_FILE_EXTRA="com.termux.openhouse.browser.RESULT_FILE"
readonly LEGACY_TIMEOUT_MS_EXTRA="com.termux.openhouse.browser.TIMEOUT_MS"
readonly LEGACY_TOKEN_EXTRA="com.termux.openhouse.browser.TOKEN"
readonly TERMUX_HOME_DIR="${OPENHOUSE_BROWSER_TERMUX_HOME:-/data/data/com.termux/files/home}"
readonly RPC_ROOT="$TERMUX_HOME_DIR/.openhouse-browser"
readonly REQUEST_DIR="$RPC_ROOT/requests"
readonly RESULT_DIR="$RPC_ROOT/results"
readonly TOKEN_FILE="$RPC_ROOT/token"
readonly DEFAULT_TIMEOUT_MS="${OPENHOUSE_BROWSER_TIMEOUT_MS:-30000}"

usage() {
  cat <<'USAGE'
Usage:
  openhouse-browser open URL
  openhouse-browser new-tab URL
  openhouse-browser switch INDEX_OR_ID
  openhouse-browser close
  openhouse-browser reload
  openhouse-browser back
  openhouse-browser forward
  openhouse-browser status
  openhouse-browser tabs
  openhouse-browser text
  openhouse-browser html
  openhouse-browser screenshot --output PATH
  openhouse-browser eval CODE
  openhouse-browser eval-file FILE
  openhouse-browser click SELECTOR
  openhouse-browser fill SELECTOR VALUE
  openhouse-browser wait selector SELECTOR --timeout MS
  openhouse-browser wait-text TEXT --timeout MS
  openhouse-browser tap X Y
  openhouse-browser type TEXT
  openhouse-browser scroll DX DY
  openhouse-browser cdp METHOD JSON_PARAMS
  openhouse-browser run FLOW_JSON
USAGE
}

die() {
  printf 'openhouse-browser: %s\n' "$*" >&2
  exit 2
}

find_am() {
  local candidate="/data/data/com.termux/files/usr/bin/am"
  if [ -x "$candidate" ]; then
    printf '%s\n' "$candidate"
    return 0
  fi

  if [ -n "${PREFIX:-}" ]; then
    candidate="$PREFIX/bin/am"
    case "$candidate" in
      /data/data/com.termux/files/usr/bin/am)
        if [ -x "$candidate" ]; then
          printf '%s\n' "$candidate"
          return 0
        fi
        ;;
    esac
  fi

  if command -v am >/dev/null 2>&1; then
    candidate="$(command -v am)"
    case "$candidate" in
      /data/data/com.termux/files/usr/bin/am)
        printf '%s\n' "$candidate"
        return 0
        ;;
    esac
  fi

  return 1
}

require_arg() {
  local command="$1"
  local value="${2:-}"
  if [ -z "$value" ]; then
    die "$command requires an argument"
  fi
}

require_no_args() {
  if [ "$#" -ne 0 ]; then
    die "unexpected extra arguments: $*"
  fi
}

require_timeout_ms() {
  local value="$1"
  if ! [[ "$value" =~ ^[0-9]+$ ]] || [ "$value" -le 0 ]; then
    die "timeout must be a positive integer in milliseconds"
  fi
}

require_number() {
  local label="$1"
  local value="$2"
  if ! [[ "$value" =~ ^-?[0-9]+([.][0-9]+)?$ ]]; then
    die "$label must be a number"
  fi
}

join_args() {
  local joined=""
  local value
  for value in "$@"; do
    if [ -n "$joined" ]; then
      joined="$joined $value"
    else
      joined="$value"
    fi
  done
  printf '%s' "$joined"
}

json_quote() {
  awk '
    BEGIN { ORS = ""; printf "\"" }
    {
      if (NR > 1) {
        printf "\\n"
      }
      gsub(/\\/, "\\\\")
      gsub(/"/, "\\\"")
      gsub(/\t/, "\\t")
      gsub(/\r/, "\\r")
      printf "%s", $0
    }
    END { printf "\"" }
  '
}

json_value() {
  printf '%s' "$1" | json_quote
}

ensure_rpc_dirs() {
  mkdir -p "$REQUEST_DIR" "$RESULT_DIR"
  chmod 700 "$RPC_ROOT" "$REQUEST_DIR" "$RESULT_DIR" || true
  if [ ! -s "$TOKEN_FILE" ]; then
    {
      date +%s%N
      printf '%s\n' "$$"
      printf '%s\n' "${RANDOM:-0}${RANDOM:-0}${RANDOM:-0}"
    } | sha256sum | awk '{print $1}' > "$TOKEN_FILE"
  fi
  chmod 600 "$TOKEN_FILE" || true
}

parse_timeout_options() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --timeout)
        require_arg "--timeout" "${2:-}"
        timeout_ms="$2"
        require_timeout_ms "$timeout_ms"
        shift 2
        ;;
      *)
        die "unexpected extra arguments: $*"
        ;;
    esac
  done
}

dispatch_browser_command() {
  local am_bin="$1"
  shift
  local extras=("$@")

  if ! "$am_bin" broadcast --user 0 -a "$COMMAND_ACTION" -n "$TARGET_RECEIVER" "${extras[@]}" >/dev/null; then
    return 12
  fi
}

legacy_start() {
  local command="$1"
  shift

  local url=""
  local tab=""

  case "$command" in
    open)
      require_arg "$command" "${1:-}"
      url="$1"
      shift
      ;;
    new-tab)
      require_arg "$command" "${1:-}"
      url="$1"
      shift
      ;;
    switch)
      require_arg "$command" "${1:-}"
      tab="$1"
      shift
      ;;
    close|reload|back|forward)
      ;;
    *)
      die "unknown command: $command"
      ;;
  esac

  require_no_args "$@"

  local am_bin
  am_bin="$(find_am)" || die "Termux activity manager 'am' not found"

  ensure_rpc_dirs

  local request_id
  request_id="$(date +%Y%m%d%H%M%S)-$$-${RANDOM:-0}"
  local request_file="$REQUEST_DIR/$request_id.json"
  local result_file="$RESULT_DIR/$request_id.json"
  local token
  token="$(cat "$TOKEN_FILE")"

  write_request_file "$request_id" "$request_file" "$command" "{}" "" "" "" "$url" "$tab"

  local extras=(
    --es "$PAGE_EXTRA" controlled_browser
    --es "$COMMAND_EXTRA" "$command"
    --es "$LEGACY_COMMAND_EXTRA" "$command"
    --es "$REQUEST_ID_EXTRA" "$request_id"
    --es "$LEGACY_REQUEST_ID_EXTRA" "$request_id"
    --es "$REQUEST_FILE_EXTRA" "$request_file"
    --es "$LEGACY_REQUEST_FILE_EXTRA" "$request_file"
    --es "$RESULT_FILE_EXTRA" "$result_file"
    --es "$LEGACY_RESULT_FILE_EXTRA" "$result_file"
    --es "$TOKEN_EXTRA" "$token"
    --es "$LEGACY_TOKEN_EXTRA" "$token"
    --ei "$TIMEOUT_MS_EXTRA" "$DEFAULT_TIMEOUT_MS"
    --ei "$LEGACY_TIMEOUT_MS_EXTRA" "$DEFAULT_TIMEOUT_MS"
  )

  if [ -n "$url" ]; then
    extras+=(--es "$URL_EXTRA" "$url")
    extras+=(--es "$LEGACY_URL_EXTRA" "$url")
  fi

  if [ -n "$tab" ]; then
    extras+=(--es "$TAB_EXTRA" "$tab")
    if [[ "$tab" =~ ^[0-9]+$ ]]; then
      extras+=(--ei "$TAB_INDEX_EXTRA" "$tab")
    fi
    extras+=(--es "$LEGACY_TAB_EXTRA" "$tab")
  fi

  dispatch_browser_command "$am_bin" "${extras[@]}" || die "failed to dispatch OpenHouse browser command"
}

write_request_file() {
  local request_id="$1"
  local request_file="$2"
  local command="$3"
  local payload_json="$4"
  local output_path="${5:-}"
  local method="${6:-}"
  local params_json="${7:-}"
  local url="${8:-}"
  local tab="${9:-}"
  local tmp_file="${request_file}.tmp.$$"

  {
    printf '{'
    printf '"requestId":'
    json_value "$request_id"
    printf ',"command":'
    json_value "$command"
    printf ',"payload":%s' "$payload_json"
    if [ -n "$url" ]; then
      printf ',"url":'
      json_value "$url"
    fi
    if [ -n "$tab" ]; then
      if [[ "$tab" =~ ^[0-9]+$ ]]; then
        printf ',"tabIndex":%s' "$tab"
      else
        printf ',"tabId":'
        json_value "$tab"
      fi
    fi
    if [ -n "$output_path" ]; then
      printf ',"output":'
      json_value "$output_path"
    fi
    if [ -n "$method" ]; then
      printf ',"method":'
      json_value "$method"
    fi
    if [ -n "$params_json" ]; then
      printf ',"params":%s' "$params_json"
    fi
    printf '}\n'
  } > "$tmp_file"
  mv "$tmp_file" "$request_file"
}

emit_failure_json() {
  local request_id="$1"
  local error="$2"
  local detail="${3:-}"
  printf '{"ok":false,"requestId":'
  json_value "$request_id"
  printf ',"error":'
  json_value "$error"
  if [ -n "$detail" ]; then
    printf ',"detail":'
    json_value "$detail"
  fi
  printf '}\n'
}

result_exit_code() {
  local result_file="$1"
  local exit_code
  exit_code="$(sed -n 's/.*"exitCode"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$result_file" | head -n 1)"
  if [ -n "$exit_code" ]; then
    if [ "$exit_code" -gt 255 ]; then
      return 1
    fi
    return "$exit_code"
  fi

  if grep -Eq '"ok"[[:space:]]*:[[:space:]]*false|"success"[[:space:]]*:[[:space:]]*false' "$result_file"; then
    return 1
  fi

  return 0
}

poll_result() {
  local request_id="$1"
  local result_file="$2"
  local timeout_ms="$3"
  local elapsed_ms=0
  local interval_ms=100

  while [ "$elapsed_ms" -lt "$timeout_ms" ]; do
    if [ -s "$result_file" ]; then
      cat "$result_file"
      result_exit_code "$result_file"
      return $?
    fi
    sleep 0.1
    elapsed_ms=$((elapsed_ms + interval_ms))
  done

  emit_failure_json "$request_id" "timeout" "result file was not written before timeout"
  return 124
}

run_rpc() {
  local command="$1"
  local payload_json="$2"
  local timeout_ms="$3"
  local output_path="${4:-}"
  local method="${5:-}"
  local params_json="${6:-}"

  require_timeout_ms "$timeout_ms"
  ensure_rpc_dirs

  local request_id
  request_id="$(date +%Y%m%d%H%M%S)-$$-${RANDOM:-0}"
  local request_file="$REQUEST_DIR/$request_id.json"
  local result_file="$RESULT_DIR/$request_id.json"
  local token
  token="$(cat "$TOKEN_FILE")"

  write_request_file "$request_id" "$request_file" "$command" "$payload_json" "$output_path" "$method" "$params_json"

  local am_bin
  am_bin="$(find_am)" || {
    emit_failure_json "$request_id" "am_not_found" "Termux activity manager am was not found"
    return 127
  }

  local extras=(
    --es "$PAGE_EXTRA" controlled_browser
    --es "$COMMAND_EXTRA" "$command"
    --es "$LEGACY_COMMAND_EXTRA" "$command"
    --es "$REQUEST_ID_EXTRA" "$request_id"
    --es "$LEGACY_REQUEST_ID_EXTRA" "$request_id"
    --es "$REQUEST_FILE_EXTRA" "$request_file"
    --es "$LEGACY_REQUEST_FILE_EXTRA" "$request_file"
    --es "$RESULT_FILE_EXTRA" "$result_file"
    --es "$LEGACY_RESULT_FILE_EXTRA" "$result_file"
    --es "$TOKEN_EXTRA" "$token"
    --es "$LEGACY_TOKEN_EXTRA" "$token"
    --ei "$TIMEOUT_MS_EXTRA" "$timeout_ms"
    --ei "$LEGACY_TIMEOUT_MS_EXTRA" "$timeout_ms"
  )

  if ! dispatch_browser_command "$am_bin" "${extras[@]}"; then
    emit_failure_json "$request_id" "am_dispatch_failed" "failed to start OpenHouse browser activity or broadcast command"
    return 1
  fi

  poll_result "$request_id" "$result_file" "$timeout_ms"
}

main() {
  if [ "$#" -lt 1 ]; then
    usage >&2
    exit 2
  fi

  local command="$1"
  shift
  local timeout_ms="$DEFAULT_TIMEOUT_MS"
  local payload_json="{}"
  local output_path=""
  local method=""
  local params_json=""

  case "$command" in
    open|new-tab|switch|close|reload|back|forward)
      legacy_start "$command" "$@"
      ;;
    status|tabs|text|html)
      require_no_args "$@"
      run_rpc "$command" "$payload_json" "$timeout_ms"
      ;;
    screenshot)
      if [ "${1:-}" != "--output" ]; then
        die "screenshot requires --output PATH"
      fi
      require_arg "screenshot --output" "${2:-}"
      output_path="$2"
      shift 2
      require_no_args "$@"
      payload_json="$(printf '{"output":'; json_value "$output_path"; printf '}')"
      run_rpc "$command" "$payload_json" "$timeout_ms" "$output_path"
      ;;
    eval)
      require_arg "$command" "${1:-}"
      local code
      code="$(join_args "$@")"
      payload_json="$(printf '{"code":'; json_value "$code"; printf '}')"
      run_rpc "$command" "$payload_json" "$timeout_ms"
      ;;
    eval-file)
      require_arg "$command" "${1:-}"
      local file="$1"
      shift
      require_no_args "$@"
      if [ ! -r "$file" ]; then
        die "eval-file cannot read: $file"
      fi
      local file_code
      file_code="$(cat "$file")"
      payload_json="$(printf '{"file":'; json_value "$file"; printf ',"code":'; json_value "$file_code"; printf '}')"
      run_rpc "$command" "$payload_json" "$timeout_ms"
      ;;
    click)
      require_arg "$command" "${1:-}"
      local selector="$1"
      shift
      require_no_args "$@"
      payload_json="$(printf '{"selector":'; json_value "$selector"; printf '}')"
      run_rpc "$command" "$payload_json" "$timeout_ms"
      ;;
    fill)
      require_arg "$command" "${1:-}"
      local fill_selector="$1"
      shift
      require_arg "$command" "${1:-}"
      local fill_value
      fill_value="$(join_args "$@")"
      payload_json="$(printf '{"selector":'; json_value "$fill_selector"; printf ',"value":'; json_value "$fill_value"; printf '}')"
      run_rpc "$command" "$payload_json" "$timeout_ms"
      ;;
    wait)
      if [ "${1:-}" != "selector" ]; then
        die "wait supports: selector"
      fi
      shift
      require_arg "wait selector" "${1:-}"
      local wait_selector="$1"
      shift
      parse_timeout_options "$@"
      payload_json="$(printf '{"kind":"selector","selector":'; json_value "$wait_selector"; printf '}')"
      run_rpc "$command" "$payload_json" "$timeout_ms"
      ;;
    wait-text)
      local text_parts=()
      while [ "$#" -gt 0 ]; do
        case "$1" in
          --timeout)
            require_arg "--timeout" "${2:-}"
            timeout_ms="$2"
            require_timeout_ms "$timeout_ms"
            shift 2
            require_no_args "$@"
            break
            ;;
          *)
            text_parts+=("$1")
            shift
            ;;
        esac
      done
      if [ "${#text_parts[@]}" -eq 0 ]; then
        die "wait-text requires TEXT"
      fi
      local wait_text
      wait_text="$(join_args "${text_parts[@]}")"
      payload_json="$(printf '{"text":'; json_value "$wait_text"; printf '}')"
      run_rpc "wait" "$payload_json" "$timeout_ms"
      ;;
    tap)
      require_arg "$command" "${1:-}"
      require_arg "$command" "${2:-}"
      local tap_x="$1"
      local tap_y="$2"
      shift 2
      require_no_args "$@"
      require_number "tap X" "$tap_x"
      require_number "tap Y" "$tap_y"
      payload_json="$(printf '{"x":%s,"y":%s}' "$tap_x" "$tap_y")"
      run_rpc "$command" "$payload_json" "$timeout_ms"
      ;;
    type)
      require_arg "$command" "${1:-}"
      local type_text
      type_text="$(join_args "$@")"
      payload_json="$(printf '{"text":'; json_value "$type_text"; printf '}')"
      run_rpc "$command" "$payload_json" "$timeout_ms"
      ;;
    scroll)
      require_arg "$command" "${1:-}"
      require_arg "$command" "${2:-}"
      local scroll_dx="$1"
      local scroll_dy="$2"
      shift 2
      require_no_args "$@"
      require_number "scroll DX" "$scroll_dx"
      require_number "scroll DY" "$scroll_dy"
      payload_json="$(printf '{"dx":%s,"dy":%s}' "$scroll_dx" "$scroll_dy")"
      run_rpc "$command" "$payload_json" "$timeout_ms"
      ;;
    cdp)
      require_arg "$command" "${1:-}"
      require_arg "$command" "${2:-}"
      method="$1"
      params_json="$2"
      shift 2
      require_no_args "$@"
      payload_json="$(printf '{"method":'; json_value "$method"; printf ',"params":%s}' "$params_json")"
      run_rpc "$command" "$payload_json" "$timeout_ms" "" "$method" "$params_json"
      ;;
    run)
      require_arg "$command" "${1:-}"
      if [ -r "$1" ]; then
        payload_json="$(cat "$1")"
      else
        payload_json="$1"
      fi
      shift
      require_no_args "$@"
      run_rpc "$command" "$payload_json" "$timeout_ms"
      ;;
    -h|--help|help)
      usage
      exit 0
      ;;
    *)
      die "unknown command: $command"
      ;;
  esac
}

main "$@"
EOF
  chmod 755 "$tmp_command"
  mv "$tmp_command" "$BROWSER_COMMAND"
  log "已注入受控浏览器命令：$BROWSER_COMMAND"

  install_controlled_browser_ubuntu_wrapper
}

install_controlled_browser_ubuntu_wrapper() {
  local ubuntu_bin_dir
  ubuntu_bin_dir="$(dirname "$UBUNTU_BROWSER_COMMAND")"
  if [ ! -d "$TERMUX_PREFIX/var/lib/proot-distro/containers/ubuntu/rootfs" ]; then
    log "Ubuntu rootfs 尚不存在，跳过 Ubuntu openhouse-browser wrapper。"
    return 0
  fi

  mkdir -p "$ubuntu_bin_dir"
  if { [ -e "$UBUNTU_BROWSER_COMMAND" ] || [ -L "$UBUNTU_BROWSER_COMMAND" ]; } && ! grep -q 'OPENHOUSE_BROWSER_MANAGED=1' "$UBUNTU_BROWSER_COMMAND" 2>/dev/null; then
    log "Ubuntu openhouse-browser 已存在且不是本安装脚本管理的文件，跳过：$UBUNTU_BROWSER_COMMAND"
    return 0
  fi

  local tmp_command="${UBUNTU_BROWSER_COMMAND}.tmp.$$"
  cat > "$tmp_command" <<'EOF'
#!/bin/sh
# OPENHOUSE_BROWSER_MANAGED=1
exec /data/data/com.termux/files/usr/bin/openhouse-browser "$@"
EOF
  chmod 755 "$tmp_command"
  mv "$tmp_command" "$UBUNTU_BROWSER_COMMAND"
  log "已注入 Ubuntu 受控浏览器命令：$UBUNTU_BROWSER_COMMAND"
}

safe_symlink() {
  local target="$1"
  local link_path="$2"
  if [ ! -e "$target" ] && [ ! -d "$target" ]; then
    return 0
  fi
  if [ -L "$link_path" ]; then
    return 0
  fi
  if [ -d "$link_path" ] && [ -z "$(find "$link_path" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]; then
    rmdir "$link_path" 2>/dev/null || true
  fi
  if [ -e "$link_path" ]; then
    log "软链接目标已存在，保留不改：$link_path"
    return 0
  fi
  ln -s "$target" "$link_path" 2>/dev/null || true
}

detect_ubuntu_rootfs_dir() {
  local candidate
  for candidate in \
    "$TERMUX_PREFIX/var/lib/proot-distro/containers/ubuntu/rootfs" \
    "$TERMUX_PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"; do
    if [ -d "$candidate/root" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

ensure_openhouse_workspace_layout() {
  log "正在准备 OpenHouse 工作区。"
  mkdir -p "$OPENHOUSE_HOME_DIR" "$WORKSPACE_DIR" \
    "$WORKSPACE_DIR/android" \
    "$WORKSPACE_DIR/termux" \
    "$WORKSPACE_DIR/ubuntu" \
    "$WORKSPACE_DIR/inbox" \
    "$WORKSPACE_DIR/export" \
    "$WORKSPACE_DIR/network" \
    "$WORKSPACE_DIR/containers"
  find "$OPENHOUSE_HOME_DIR" "$WORKSPACE_DIR" -maxdepth 1 -type d -exec chmod 700 {} + 2>/dev/null || true

  if [ -d "$TERMUX_HOME/storage/shared" ]; then
    mkdir -p "$ANDROID_SHARED_OPENHOUSE_DIR" 2>/dev/null || true
    safe_symlink "$TERMUX_HOME/storage/shared" "$WORKSPACE_DIR/android/shared"
    safe_symlink "$ANDROID_SHARED_OPENHOUSE_DIR" "$WORKSPACE_DIR/android/openhouse"
  fi
  safe_symlink "$TERMUX_HOME" "$WORKSPACE_DIR/termux/home"

  local ubuntu_rootfs
  if ubuntu_rootfs="$(detect_ubuntu_rootfs_dir 2>/dev/null)"; then
    mkdir -p "$ubuntu_rootfs/root/openhouse/workspace" 2>/dev/null || true
    safe_symlink "$ubuntu_rootfs/root" "$WORKSPACE_DIR/ubuntu/root"
    safe_symlink "$ubuntu_rootfs/root/openhouse/workspace" "$WORKSPACE_DIR/ubuntu/workspace"
  fi

  if [ -L "$LEGACY_WORKSPACE_DIR" ] || [ ! -e "$LEGACY_WORKSPACE_DIR" ]; then
    safe_symlink "$WORKSPACE_DIR" "$LEGACY_WORKSPACE_DIR"
  else
    log "兼容工作区已存在且不是软链接，保留不改：$LEGACY_WORKSPACE_DIR"
  fi
}

log "正在确保基础目录存在。"
mkdir -p "$DOC_DIR" "$LEGACY_DOC_DIR" "$TERMUX_CONFIG_DIR"
chmod 700 "$DOC_DIR" "$LEGACY_DOC_DIR" "$TERMUX_CONFIG_DIR" || true
ensure_openhouse_workspace_layout

log "正在启用 allow-external-apps。"
touch "$TERMUX_PROPERTIES_FILE"
if grep -q '^[[:space:]]*allow-external-apps' "$TERMUX_PROPERTIES_FILE"; then
  sed -i 's/^[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps = true/' "$TERMUX_PROPERTIES_FILE"
else
  printf '\nallow-external-apps = true\n' >> "$TERMUX_PROPERTIES_FILE"
fi

cat > "$DOC_DIR/README.md" <<'EOF'
# SmallPhoneAI 文档

本目录用于保存 SmallPhoneAI 文档和本机笔记。

正式说明会由“同步官方文档”阶段写入 `official/`：
- `official/ENVIRONMENT.md`
- `official/MODEL_API_SETUP.md`
- `official/OPTIONAL_EXTERNAL_TOOLS.md`
EOF

cat > "$DOC_DIR/ENVIRONMENT.md" <<'EOF'
# 运行环境说明

SmallPhoneAI 运行在 Android Termux 中，并通过 `proot-distro` 提供 Ubuntu。默认核心能力是 Termux/Ubuntu 基础环境、Node.js、OpenHouse 文档、pi-agent、pi-web、service-manager 和 SmallPhone 兼容服务。cc-connect/openhouse-connect 会保留为可安装、可注册、可诊断、可修复的可选连接服务，但不作为首次 readiness 必需项。Codex CLI、Claude Code、CloudCLI 和 Hermes 是后置 AI 工作能力，由 pi-agent 按 `/root/openhouse/docs` 和 `/root/openhouse/scripts` 引导安装、检查和注册。

外部可选工具不作为内置组件打包进 APK，也不是首次安装默认阶段。如需自行下载、配置或迁移旧工具，请参考产品手册 `official/OPTIONAL_EXTERNAL_TOOLS.md`。

工作区路径：`/data/data/com.termux/files/home/openhouse/workspace`

兼容路径：`/data/data/com.termux/files/home/workspace`

App Shell 可读取：

```bash
bash bootstrap.sh status
bash bootstrap.sh hooks
```
EOF

cat > "$DOC_DIR/MODEL_API_SETUP.md" <<'EOF'
# Codex、Claude Code 和 CloudCLI 登录/API 配置

正式配置说明会由“同步官方文档”阶段写入 `official/MODEL_API_SETUP.md`。

外部可选工具不内置不进 APK；相关下载和配置说明见 `official/OPTIONAL_EXTERNAL_TOOLS.md`。

不要把 API key 写入 git 仓库、共享文档、APK 资源、日志或截图。
EOF

install_env_probe_cli
install_controlled_browser_cli

log "文档路径：$DOC_DIR"
log "兼容文档路径：$LEGACY_DOC_DIR"
log "工作区路径：$WORKSPACE_DIR"
log "兼容工作区路径：$LEGACY_WORKSPACE_DIR"
log "Termux 配置：$TERMUX_PROPERTIES_FILE"
log "Termux 路径、配置和文档准备完成。"
