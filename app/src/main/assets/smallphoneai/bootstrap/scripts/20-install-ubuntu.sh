#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/_ubuntu-mirror-policy.sh" ]; then
  # shellcheck source=_ubuntu-mirror-policy.sh
  . "$SCRIPT_DIR/_ubuntu-mirror-policy.sh"
fi
if [ -f "$SCRIPT_DIR/_retry-profile.sh" ]; then
  # shellcheck source=_retry-profile.sh
  . "$SCRIPT_DIR/_retry-profile.sh"
fi

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

run_logged() {
  log "+ $*"
  "$@"
}

shell_quote() {
  printf '%q' "$1"
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
if command -v smallphoneai_log_retry_profile >/dev/null 2>&1; then
  smallphoneai_log_retry_profile '[SmallPhoneAI]'
fi

if is_current_ubuntu; then
  log "当前已在 Ubuntu 内，无需安装 Ubuntu rootfs。"
  exit 0
fi

if ! is_termux; then
  log "Ubuntu rootfs 安装阶段只能在 Termux 外层运行。当前运行环境：$(detect_smallphoneai_runtime)"
  exit 2
fi

TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
UBUNTU_PROBE_STAGING="$TERMUX_HOME/.smallphoneai-bootstrap/smallphoneai-env-probe-ubuntu.sh"
UBUNTU_WORKSPACE_STAGING="$TERMUX_HOME/.smallphoneai-bootstrap/ensure-openhouse-workspace-ubuntu.sh"
UBUNTU_TERMUX_BRIDGE_STAGING="$TERMUX_HOME/.smallphoneai-bootstrap/openhouse-termux-ubuntu.sh"
OPENHOUSE_HOME_DIR="$TERMUX_HOME/openhouse"
TERMUX_WORKSPACE_DIR="$OPENHOUSE_HOME_DIR/workspace"
LEGACY_WORKSPACE_DIR="$TERMUX_HOME/workspace"

ubuntu_retry_mode() {
  if command -v smallphoneai_retry_mode >/dev/null 2>&1; then
    smallphoneai_retry_mode
    return 0
  fi

  local raw
  raw="${OPENHOUSE_RETRY_MODE:-${SMALLPHONEAI_RETRY_MODE:-normal}}"
  raw="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  case "$raw" in
    cn|china|mainland|domestic|china-mainland) printf 'cn' ;;
    *) printf 'normal' ;;
  esac
}

ubuntu_rootfs_arch() {
  if command -v smallphoneai_ubuntu_rootfs_arch >/dev/null 2>&1; then
    smallphoneai_ubuntu_rootfs_arch "${SMALLPHONEAI_UBUNTU_ROOTFS_ARCH:-$(uname -m)}"
    return $?
  fi
  local machine
  machine="${SMALLPHONEAI_UBUNTU_ROOTFS_ARCH:-$(uname -m)}"
  case "$machine" in
    aarch64|arm64) printf 'arm64' ;;
    x86_64|amd64) printf 'amd64' ;;
    arm|armv7l|armv8l|armhf) printf 'armhf' ;;
    *)
      log "不支持为当前架构下载 Ubuntu cloud rootfs：$machine"
      return 1
      ;;
  esac
}

ubuntu_rootfs_candidates() {
  local arch
  arch="$(ubuntu_rootfs_arch)"
  if ! command -v smallphoneai_ubuntu_rootfs_effective_candidates >/dev/null 2>&1; then
    log "缺少 canonical Ubuntu mirror policy：$SCRIPT_DIR/_ubuntu-mirror-policy.sh"
    return 1
  fi
  smallphoneai_ubuntu_rootfs_effective_candidates "$arch"
}

ubuntu_rootfs_download_candidates() {
  local selected="$1" candidate_list="$2" candidate found=0
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    if [ "$candidate" = "$selected" ]; then
      found=1
    fi
    [ "$found" -eq 1 ] && printf '%s\n' "$candidate"
  done <<< "$candidate_list"
  if [ "$found" -eq 0 ]; then
    printf '%s\n' "$selected"
  fi
}

ubuntu_rootfs_debian_arch() {
  ubuntu_rootfs_arch
}

normalize_ubuntu_rootfs_url() {
  local url="$1" arch
  arch="$(ubuntu_rootfs_arch)"
  url="${url//\{arch\}/$arch}"
  url="${url//-arm64-root.tar.xz/-${arch}-root.tar.xz}"
  url="${url//-amd64-root.tar.xz/-${arch}-root.tar.xz}"
  url="${url//-armhf-root.tar.xz/-${arch}-root.tar.xz}"
  printf '%s\n' "$url"
}

ubuntu_rootfs_min_bytes() {
  printf '%s\n' "${SMALLPHONEAI_UBUNTU_ROOTFS_MIN_BYTES:-52428800}"
}

ubuntu_rootfs_expected_size() {
  local url="$1"
  curl -fsSIL --connect-timeout 12 --max-time 45 "$url" 2>/dev/null \
    | tr -d '\r' \
    | awk 'tolower($1)=="content-length:" {size=$2} END {if (size ~ /^[0-9]+$/) print size}'
}

ubuntu_curl_retry_args() {
  local attempts=4 source_timeout=1800
  if [ "$(ubuntu_retry_mode)" = "cn" ]; then
    attempts=8
  fi

  # Retries are controlled below so a partial file can be resumed between attempts.
  UBUNTU_CURL_ATTEMPTS="$attempts"
  UBUNTU_CURL_SOURCE_TIMEOUT="${SMALLPHONEAI_UBUNTU_ROOTFS_SOURCE_TIMEOUT:-$source_timeout}"
  UBUNTU_CURL_RETRY_ARGS=(
    --fail --location --show-error
    --connect-timeout 15
    --speed-limit 1024
    --speed-time 120
  )
}

validate_ubuntu_rootfs_archive() {
  local archive="$1" headers="$2" listing="$3"
  local actual_size expected_size min_bytes entries dpkg_entry archive_arch expected_arch

  actual_size="$(wc -c < "$archive" | tr -d '[:space:]')"
  min_bytes="$(ubuntu_rootfs_min_bytes)"
  if ! [[ "$min_bytes" =~ ^[0-9]+$ ]] || [ "$min_bytes" -lt 1 ]; then
    log "Ubuntu rootfs 最小体积配置无效：$min_bytes"
    return 1
  fi
  if [ "$actual_size" -lt "$min_bytes" ]; then
    log "Ubuntu rootfs 下载不完整：${actual_size} bytes，小于最低要求 ${min_bytes} bytes。"
    return 1
  fi

  expected_size="$(tr -d '\r' < "$headers" | awk '
    $1 ~ /^HTTP\// {content_length=""; range_total=""}
    tolower($1)=="content-range:" {split($3, parts, "/"); if (parts[2] ~ /^[0-9]+$/) range_total=parts[2]}
    tolower($1)=="content-length:" {content_length=$2}
    END {
      size=(range_total ~ /^[0-9]+$/) ? range_total : content_length
      if (size ~ /^[0-9]+$/) print size
    }
  ')"
  if [ -n "$expected_size" ] && [ "$expected_size" -ge "$min_bytes" ] && [ "$actual_size" -ne "$expected_size" ]; then
    log "Ubuntu rootfs Content-Length 不匹配：expected=$expected_size actual=$actual_size"
    return 1
  fi

  if ! xz -t "$archive"; then
    log "Ubuntu rootfs xz 完整性校验失败。"
    return 1
  fi
  if ! tar -tf "$archive" > "$listing"; then
    log "Ubuntu rootfs tar 目录校验失败。"
    return 1
  fi

  entries="$(wc -l < "$listing" | tr -d '[:space:]')"
  if [ "$entries" -lt 1000 ] \
    || ! grep -Eq '(^|/)(etc/os-release|usr/lib/os-release)$' "$listing" \
    || ! grep -Eq '(^|/)usr/bin/(bash|sh)$' "$listing"; then
    log "下载内容不是完整的 Ubuntu rootfs：entries=$entries"
    return 1
  fi

  dpkg_entry="$(awk '/(^|\/)var\/lib\/dpkg\/arch$/ {print; exit}' "$listing")"
  if [ -n "$dpkg_entry" ]; then
    archive_arch="$(tar -xOf "$archive" "$dpkg_entry" 2>/dev/null | awk 'NF && !found {print; found=1}')"
    expected_arch="$(ubuntu_rootfs_debian_arch)"
    if [ -n "$archive_arch" ] && [ "$archive_arch" != "$expected_arch" ]; then
      log "Ubuntu rootfs 架构不匹配：expected=$expected_arch actual=$archive_arch"
      return 1
    fi
  fi

  log "Ubuntu rootfs 归档校验通过：${actual_size} bytes，${entries} entries。"
}

download_rootfs_with_resume() {
  local url="$1" partial="$2" headers="$3"
  local attempt=1 existing=0 response_code range_total remaining deadline now

  [ -f "$partial" ] && existing="$(wc -c < "$partial" | tr -d '[:space:]')"
  deadline=$(( $(date +%s) + UBUNTU_CURL_SOURCE_TIMEOUT ))

  while [ "$attempt" -le "$UBUNTU_CURL_ATTEMPTS" ]; do
    now="$(date +%s)"
    remaining=$((deadline - now))
    if [ "$remaining" -le 0 ]; then
      log "Ubuntu rootfs 来源达到单源时间预算：$url"
      return 1
    fi

    rm -f "$headers"
    if [ "$existing" -gt 0 ]; then
      log "Ubuntu rootfs 断点续传：${existing} bytes，尝试 ${attempt}/${UBUNTU_CURL_ATTEMPTS}"
      if curl "${UBUNTU_CURL_RETRY_ARGS[@]}" --max-time "$remaining" -C - -D "$headers" -o "$partial" "$url"; then
        response_code="$(tr -d '\r' < "$headers" | awk '$1 ~ /^HTTP\// {code=$2} END {print code}')"
        if [ "$response_code" = "200" ]; then
          log "服务器未提供 Range，改为从头完整下载：$url"
          rm -f "$partial"
          existing=0
          attempt=$((attempt + 1))
          continue
        fi
        if [ "$response_code" = "206" ]; then
          return 0
        fi
      else
        response_code="$(tr -d '\r' < "$headers" | awk '$1 ~ /^HTTP\// {code=$2} END {print code}')"
        if [ "$response_code" = "200" ]; then
          log "服务器不支持 Range，清理部分归档后完整下载：$url"
          rm -f "$partial"
          existing=0
        elif [ "$response_code" = "416" ]; then
          range_total="$(tr -d '\r' < "$headers" | awk 'tolower($1)=="content-range:" {split($3, parts, "/"); total=parts[2]} END {if (total ~ /^[0-9]+$/) print total}')"
          existing="$(wc -c < "$partial" | tr -d '[:space:]')"
          if [ -n "$range_total" ] && [ "$existing" -eq "$range_total" ]; then
            log "Ubuntu rootfs 已完整下载，服务器返回 Range 416：${existing} bytes。"
            return 0
          fi
        fi
      fi
    else
      log "Ubuntu rootfs 下载尝试 ${attempt}/${UBUNTU_CURL_ATTEMPTS}：$url"
      if curl "${UBUNTU_CURL_RETRY_ARGS[@]}" --max-time "$remaining" -D "$headers" -o "$partial" "$url"; then
        return 0
      fi
    fi

    [ -f "$partial" ] && existing="$(wc -c < "$partial" | tr -d '[:space:]')" || existing=0
    attempt=$((attempt + 1))
    if [ "$attempt" -le "$UBUNTU_CURL_ATTEMPTS" ]; then
      sleep 2
    fi
  done

  log "Ubuntu rootfs 来源在 ${UBUNTU_CURL_ATTEMPTS} 次尝试内未完成：$url"
  return 1
}

cleanup_failed_ubuntu_install() {
  proot-distro remove ubuntu >/dev/null 2>&1 || true
  rm -rf \
    "${PREFIX:-/data/data/com.termux/files/usr}/var/lib/proot-distro/installed-rootfs/ubuntu" \
    "${PREFIX:-/data/data/com.termux/files/usr}/var/lib/proot-distro/containers/ubuntu"
  rm -f "${PREFIX:-/data/data/com.termux/files/usr}/etc/proot-distro/ubuntu.sh"
}

download_and_install_ubuntu_rootfs() (
  local cache_root tmp_dir archive partial headers listing url normalized_url expected_size min_bytes selected_url arch candidate_list

  if ! command -v curl >/dev/null 2>&1 || ! command -v xz >/dev/null 2>&1 || ! command -v tar >/dev/null 2>&1; then
    log "下载 Ubuntu rootfs 需要 curl、xz 和 tar。"
    return 1
  fi

  cache_root="${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}"
  mkdir -p "$cache_root"
  tmp_dir="$(mktemp -d "$cache_root/openhouse-ubuntu-rootfs.XXXXXX")"
  trap 'rm -rf "$tmp_dir"' EXIT INT TERM
  archive="$tmp_dir/ubuntu-rootfs.tar.xz"
  partial="$archive.part"
  headers="$tmp_dir/headers"
  listing="$tmp_dir/listing"
  min_bytes="$(ubuntu_rootfs_min_bytes)"
  if ! [[ "$min_bytes" =~ ^[0-9]+$ ]] || [ "$min_bytes" -lt 1 ]; then
    log "Ubuntu rootfs 最小体积配置无效：$min_bytes"
    return 1
  fi
  ubuntu_curl_retry_args
  if ! [[ "$UBUNTU_CURL_SOURCE_TIMEOUT" =~ ^[0-9]+$ ]] || [ "$UBUNTU_CURL_SOURCE_TIMEOUT" -lt 60 ]; then
    log "Ubuntu rootfs 单源时间预算无效：$UBUNTU_CURL_SOURCE_TIMEOUT"
    return 1
  fi

  if ! command -v smallphoneai_resolve_ubuntu_rootfs_url >/dev/null 2>&1; then
    log "canonical Ubuntu rootfs resolver 不可用。"
    return 1
  fi
  arch="$(ubuntu_rootfs_arch)"
  candidate_list="$(smallphoneai_ubuntu_rootfs_effective_candidates "$arch")" || {
    log "canonical Ubuntu rootfs 候选列表无效。"
    return 1
  }
  selected_url="$(smallphoneai_resolve_ubuntu_rootfs_url "$arch")" || {
    log "canonical Ubuntu rootfs mirror 解析失败。"
    return 1
  }
  export OPENHOUSEAI_UBUNTU_ROOTFS_URL="$selected_url"
  export SMALLPHONEAI_UBUNTU_ROOTFS_URL="$selected_url"
  export OPENHOUSEAI_RESOLVED_UBUNTU_ROOTFS_URL="$selected_url"
  export SMALLPHONEAI_RESOLVED_UBUNTU_ROOTFS_URL="$selected_url"
  log "canonical Ubuntu rootfs 首选来源：$selected_url"

  while IFS= read -r url; do
    [ -n "$url" ] || continue
    normalized_url="$(normalize_ubuntu_rootfs_url "$url")"
    expected_size="$(ubuntu_rootfs_expected_size "$normalized_url" || true)"
    if [ -n "$expected_size" ] && [ "$expected_size" -lt "$min_bytes" ]; then
      log "跳过体积异常的 Ubuntu rootfs 源：$normalized_url (${expected_size} bytes)"
      continue
    fi

    rm -f "$partial" "$archive" "$headers" "$listing"
    log "正在完整下载 Ubuntu rootfs：$normalized_url"
    if ! download_rootfs_with_resume "$normalized_url" "$partial" "$headers"; then
      log "Ubuntu rootfs 下载失败，尝试下一个来源。"
      rm -f "$partial"
      continue
    fi
    mv "$partial" "$archive"

    if ! validate_ubuntu_rootfs_archive "$archive" "$headers" "$listing"; then
      log "Ubuntu rootfs 校验失败，尝试下一个来源。"
      rm -f "$archive"
      continue
    fi

    cleanup_failed_ubuntu_install
    log "正在从已校验的本地归档安装 Ubuntu：$archive"
    if run_logged proot-distro install -n ubuntu "$archive" \
      && proot-distro login ubuntu -- true >/dev/null 2>&1; then
      log "Ubuntu rootfs 安装并登录验证成功。"
      return 0
    fi

    log "Ubuntu rootfs 安装失败，清理半成品后尝试下一个来源。"
    cleanup_failed_ubuntu_install
  done < <(ubuntu_rootfs_download_candidates "$selected_url" "$candidate_list" | awk 'NF && !seen[$0]++')

  cleanup_failed_ubuntu_install
  log "所有 Ubuntu rootfs 来源均下载或安装失败。"
  return 1
)

install_ubuntu_env_probe_cli() {
  if proot-distro login ubuntu -- bash -lc 'test -x "$HOME/bin/smallphoneai-env-probe"' >/dev/null 2>&1; then
    log "Ubuntu 侧环境探测 CLI 已存在。"
    return 0
  fi

  mkdir -p "$(dirname "$UBUNTU_PROBE_STAGING")"
  cat > "$UBUNTU_PROBE_STAGING" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

INSTALL_SIDE="ubuntu"

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
  chmod 755 "$UBUNTU_PROBE_STAGING"
  local probe_staging_q
  probe_staging_q="$(shell_quote "$UBUNTU_PROBE_STAGING")"
  run_logged proot-distro login ubuntu -- bash -lc "mkdir -p \"\$HOME/bin\"; cp $probe_staging_q \"\$HOME/bin/smallphoneai-env-probe\"; chmod 755 \"\$HOME/bin/smallphoneai-env-probe\"; \"\$HOME/bin/smallphoneai-env-probe\""
  rm -f "$UBUNTU_PROBE_STAGING"
  log "已注入 Ubuntu 侧环境探测 CLI：~/bin/smallphoneai-env-probe"
}

install_ubuntu_termux_bridge_cli() {
  log "正在注入 Ubuntu -> Termux native 桥接 CLI。"
  mkdir -p "$(dirname "$UBUNTU_TERMUX_BRIDGE_STAGING")"
  cat > "$UBUNTU_TERMUX_BRIDGE_STAGING" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

TERMUX_HOME_DIR="${OPENHOUSE_TERMUX_HOME:-/data/data/com.termux/files/home}"
TERMUX_PREFIX_DIR="${OPENHOUSE_TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_PACKAGE="${OPENHOUSE_TERMUX_PACKAGE:-com.termux}"
TERMUX_SSH_HOST="${OPENHOUSE_TERMUX_SSH_HOST:-127.0.0.1}"
TERMUX_SSH_PORT="${OPENHOUSE_TERMUX_SSH_PORT:-}"
TERMUX_SSH_USER="${OPENHOUSE_TERMUX_SSH_USER:-}"
TERMUX_SSH_PORT_FILE="$TERMUX_HOME_DIR/.smallphoneai/termux-ssh-port"
TERMUX_SSH_USER_FILE="$TERMUX_HOME_DIR/.smallphoneai/termux-ssh-user"
TERMUX_BRIDGE_KEY="${OPENHOUSE_TERMUX_BRIDGE_KEY:-$TERMUX_HOME_DIR/.ssh/openhouse_termux_bridge_ed25519}"
TERMUX_ENSURE_COMMAND="${OPENHOUSE_TERMUX_ENSURE_COMMAND:-$TERMUX_PREFIX_DIR/bin/oh-termux-ensure-sshd}"
KNOWN_HOSTS="${OPENHOUSE_TERMUX_KNOWN_HOSTS:-$HOME/.ssh/openhouse_termux_known_hosts}"

usage() {
  cat >&2 <<'USAGE'
Usage:
  openhouse-termux status --json
  openhouse-termux ensure-sshd
  openhouse-termux exec -- COMMAND [ARG...]

Short alias: oh-termux
USAGE
}

json_quote() {
  awk '
    BEGIN { ORS = ""; printf "\"" }
    {
      if (NR > 1) printf "\\n"
      gsub(/\\/, "\\\\")
      gsub(/"/, "\\\"")
      gsub(/\t/, "\\t")
      gsub(/\r/, "\\r")
      printf "%s", $0
    }
    END { printf "\"" }
  '
}

emit_error_json() {
  local code="$1"
  local message="$2"
  printf '{"ok":false,"provider":"termux-sshd","code":'
  printf '%s' "$code" | json_quote
  printf ',"message":'
  printf '%s' "$message" | json_quote
  printf '}\n'
}

load_bridge_state() {
  if [ -z "${OPENHOUSE_TERMUX_SSH_PORT:-}" ] && [ -s "$TERMUX_SSH_PORT_FILE" ]; then
    TERMUX_SSH_PORT="$(sed -n '1p' "$TERMUX_SSH_PORT_FILE" 2>/dev/null || true)"
  fi
  TERMUX_SSH_PORT="${TERMUX_SSH_PORT:-8022}"

  if [ -z "${OPENHOUSE_TERMUX_SSH_USER:-}" ] && [ -s "$TERMUX_SSH_USER_FILE" ]; then
    TERMUX_SSH_USER="$(sed -n '1p' "$TERMUX_SSH_USER_FILE" 2>/dev/null || true)"
  fi
}

ssh_args() {
  mkdir -p "$(dirname "$KNOWN_HOSTS")"
  chmod 700 "$(dirname "$KNOWN_HOSTS")" 2>/dev/null || true
  load_bridge_state
  printf '%s\0' \
    -p "$TERMUX_SSH_PORT" \
    -o BatchMode=yes \
    -o ConnectTimeout=5 \
    -o StrictHostKeyChecking=accept-new \
    -o UserKnownHostsFile="$KNOWN_HOSTS" \
    -o LogLevel=ERROR
  if [ -s "$TERMUX_BRIDGE_KEY" ]; then
    printf '%s\0' -i "$TERMUX_BRIDGE_KEY"
  fi
  if [ -n "$TERMUX_SSH_USER" ]; then
    printf '%s\0' -l "$TERMUX_SSH_USER"
  fi
  printf '%s\0' "$TERMUX_SSH_HOST"
}

run_ssh() {
  local args=()
  while IFS= read -r -d '' arg; do
    args+=("$arg")
  done < <(ssh_args)
  ssh "${args[@]}" "$@"
}

ssh_ready() {
  run_ssh true >/dev/null 2>&1
}

trigger_run_command_ensure() {
  local am_bin action component
  am_bin="$(command -v am 2>/dev/null || true)"
  if [ -z "$am_bin" ] && [ -x /system/bin/am ]; then
    am_bin=/system/bin/am
  fi
  [ -n "$am_bin" ] || return 127
  [ -x "$TERMUX_ENSURE_COMMAND" ] || return 126

  action="$TERMUX_PACKAGE.RUN_COMMAND"
  component="$TERMUX_PACKAGE/.app.RunCommandService"
  "$am_bin" startservice --user 0 \
    -n "$component" \
    -a "$action" \
    --es "$TERMUX_PACKAGE.RUN_COMMAND_PATH" "$TERMUX_ENSURE_COMMAND" \
    --esa "$TERMUX_PACKAGE.RUN_COMMAND_ARGUMENTS" "ensure" \
    --ez "$TERMUX_PACKAGE.RUN_COMMAND_BACKGROUND" true \
    >/dev/null
}

ensure_sshd() {
  local attempt
  if ssh_ready; then
    return 0
  fi

  trigger_run_command_ensure || true
  for attempt in 1 2 3 4 5; do
    sleep 1
    load_bridge_state
    if ssh_ready; then
      return 0
    fi
  done

  printf '%s\n' "openhouse-termux: Termux sshd is not reachable on $TERMUX_SSH_HOST:$TERMUX_SSH_PORT." >&2
  printf '%s\n' "Run this in Termux native: oh-termux-ensure-sshd ensure" >&2
  return 1
}

status_json() {
  if ! ensure_sshd >/dev/null 2>&1; then
    emit_error_json "termux_ssh_unreachable" "Termux sshd is not reachable; run oh-termux-ensure-sshd ensure in Termux native"
    return 1
  fi

  run_ssh 'node_runtime="$(node -p '"'"'process.platform+"/"+process.arch'"'"' 2>/dev/null || true)"; printf "{\"ok\":true,\"provider\":\"termux-sshd\",\"runtime\":\"termux\",\"user\":\"%s\",\"uid\":\"%s\",\"home\":\"%s\",\"prefix\":\"%s\",\"node\":\"%s\"}\n" "$(id -un 2>/dev/null || true)" "$(id -u 2>/dev/null || true)" "${HOME:-}" "${PREFIX:-}" "$node_runtime"'
}

case "${1:-}" in
  status)
    shift
    if [ "${1:-}" = "--json" ] || [ "$#" -eq 0 ]; then
      status_json
    else
      usage
      exit 2
    fi
    ;;
  ensure-sshd|ensure)
    ensure_sshd
    status_json
    ;;
  exec)
    shift
    if [ "${1:-}" = "--" ]; then
      shift
    fi
    if [ "$#" -eq 0 ]; then
      usage
      exit 2
    fi
    ensure_sshd
    run_ssh "$@"
    ;;
  ""|-h|--help|help)
    usage
    ;;
  *)
    usage
    exit 2
    ;;
esac
EOF
  chmod 755 "$UBUNTU_TERMUX_BRIDGE_STAGING"
  local termux_bridge_staging_q
  termux_bridge_staging_q="$(shell_quote "$UBUNTU_TERMUX_BRIDGE_STAGING")"
  run_logged proot-distro login ubuntu -- bash -lc "cp $termux_bridge_staging_q /usr/local/bin/openhouse-termux; chmod 755 /usr/local/bin/openhouse-termux; ln -sf /usr/local/bin/openhouse-termux /usr/local/bin/oh-termux"
  rm -f "$UBUNTU_TERMUX_BRIDGE_STAGING"
  log "已注入 Ubuntu -> Termux native 桥接 CLI：/usr/local/bin/openhouse-termux、/usr/local/bin/oh-termux"
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
    "${PREFIX:-/data/data/com.termux/files/usr}/var/lib/proot-distro/containers/ubuntu/rootfs" \
    "${PREFIX:-/data/data/com.termux/files/usr}/var/lib/proot-distro/installed-rootfs/ubuntu"; do
    if [ -d "$candidate/root" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

ensure_termux_workspace_layout() {
  log "正在准备 Termux 侧 OpenHouse 工作区。"
  mkdir -p "$OPENHOUSE_HOME_DIR" "$TERMUX_WORKSPACE_DIR" \
    "$TERMUX_WORKSPACE_DIR/android" \
    "$TERMUX_WORKSPACE_DIR/termux" \
    "$TERMUX_WORKSPACE_DIR/ubuntu" \
    "$TERMUX_WORKSPACE_DIR/inbox" \
    "$TERMUX_WORKSPACE_DIR/export" \
    "$TERMUX_WORKSPACE_DIR/network" \
    "$TERMUX_WORKSPACE_DIR/containers"
  find "$OPENHOUSE_HOME_DIR" "$TERMUX_WORKSPACE_DIR" -maxdepth 1 -type d -exec chmod 700 {} + 2>/dev/null || true

  safe_symlink "$TERMUX_HOME" "$TERMUX_WORKSPACE_DIR/termux/home"
  if [ -d "$TERMUX_HOME/storage/shared" ]; then
    mkdir -p "$TERMUX_HOME/storage/shared/OpenHouse" 2>/dev/null || true
    safe_symlink "$TERMUX_HOME/storage/shared" "$TERMUX_WORKSPACE_DIR/android/shared"
    safe_symlink "$TERMUX_HOME/storage/shared/OpenHouse" "$TERMUX_WORKSPACE_DIR/android/openhouse"
  fi

  local ubuntu_rootfs
  if ubuntu_rootfs="$(detect_ubuntu_rootfs_dir 2>/dev/null)"; then
    mkdir -p "$ubuntu_rootfs/root/openhouse/workspace" 2>/dev/null || true
    safe_symlink "$ubuntu_rootfs/root" "$TERMUX_WORKSPACE_DIR/ubuntu/root"
    safe_symlink "$ubuntu_rootfs/root/openhouse/workspace" "$TERMUX_WORKSPACE_DIR/ubuntu/workspace"
  fi

  if [ -L "$LEGACY_WORKSPACE_DIR" ] || [ ! -e "$LEGACY_WORKSPACE_DIR" ]; then
    safe_symlink "$TERMUX_WORKSPACE_DIR" "$LEGACY_WORKSPACE_DIR"
  else
    log "兼容工作区已存在且不是软链接，保留不改：$LEGACY_WORKSPACE_DIR"
  fi
}

ensure_ubuntu_workspace_layout() {
  log "正在准备 Ubuntu 侧 OpenHouse 工作区。"
  mkdir -p "$(dirname "$UBUNTU_WORKSPACE_STAGING")"
  cat > "$UBUNTU_WORKSPACE_STAGING" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

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
    return 0
  fi
  ln -s "$target" "$link_path" 2>/dev/null || true
}

workspace="/root/openhouse/workspace"
mkdir -p "$workspace/android" \
  "$workspace/termux" \
  "$workspace/ubuntu" \
  "$workspace/inbox" \
  "$workspace/export" \
  "$workspace/network" \
  "$workspace/containers"
chmod 700 "/root/openhouse" "$workspace" 2>/dev/null || true

if [ -L "/root/workspace" ] || [ ! -e "/root/workspace" ]; then
  safe_symlink "$workspace" "/root/workspace"
fi

safe_symlink "/root" "$workspace/ubuntu/root"
safe_symlink "/data/data/com.termux/files/home" "$workspace/termux/home"
safe_symlink "/data/data/com.termux/files/home/openhouse/workspace" "$workspace/termux/workspace"

for android_shared in "/sdcard" "/storage/emulated/0" "/data/data/com.termux/files/home/storage/shared"; do
  if [ -d "$android_shared" ]; then
    mkdir -p "$android_shared/OpenHouse" 2>/dev/null || true
    safe_symlink "$android_shared" "$workspace/android/shared"
    safe_symlink "$android_shared/OpenHouse" "$workspace/android/openhouse"
    break
  fi
done
EOF
  chmod 755 "$UBUNTU_WORKSPACE_STAGING"
  run_logged proot-distro login ubuntu -- bash "$UBUNTU_WORKSPACE_STAGING"
  rm -f "$UBUNTU_WORKSPACE_STAGING"
}

if ! command -v proot-distro >/dev/null 2>&1; then
  log "缺少 proot-distro，请先运行：bash bootstrap.sh prepare"
  exit 2
fi

ubuntu_was_present=0
if proot-distro login ubuntu -- true >/dev/null 2>&1; then
  log "Ubuntu 已安装。"
  ubuntu_was_present=1
else
  download_and_install_ubuntu_rootfs
fi

install_ubuntu_env_probe_cli
install_ubuntu_termux_bridge_cli
ensure_termux_workspace_layout
ensure_ubuntu_workspace_layout

if proot-distro login ubuntu -- true >/dev/null 2>&1; then
  if [ "$ubuntu_was_present" -eq 1 ]; then
    log "Ubuntu rootfs 已可登录。"
  else
    log "Ubuntu 安装完成。"
  fi
else
  log "Ubuntu 安装后未生成可用 rootfs。"
  exit 1
fi
