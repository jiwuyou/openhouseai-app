TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
UBUNTU_PROBE_STAGING="$TERMUX_HOME/.maintainer-logs/openhouseai-env-probe-ubuntu.sh"

shell_quote() {
  printf '%q' "$1"
}

ubuntu_retry_mode() {
  local raw
  raw="${OPENHOUSEAI_RETRY_MODE:-${OPENHOUSE_RETRY_MODE:-${SMALLPHONEAI_RETRY_MODE:-normal}}}"
  raw="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  case "$raw" in
    cn|china|mainland|domestic|china-mainland) printf 'cn' ;;
    *) printf 'normal' ;;
  esac
}

ubuntu_rootfs_arch() {
  local machine
  machine="${OPENHOUSEAI_UBUNTU_ROOTFS_ARCH:-$(uname -m)}"
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
  local arch mode
  arch="$(ubuntu_rootfs_arch)"
  mode="$(ubuntu_retry_mode)"

  if [ -n "${OPENHOUSEAI_UBUNTU_ROOTFS_URL:-}" ]; then
    printf '%s\n' "$OPENHOUSEAI_UBUNTU_ROOTFS_URL"
  fi
  if [ "$mode" != "cn" ] && [ -n "${OPENHOUSEAI_UBUNTU_ROOTFS_URLS:-}" ]; then
    printf '%s\n' "$OPENHOUSEAI_UBUNTU_ROOTFS_URLS"
  fi

  if [ "$mode" = "cn" ]; then
    printf '%s\n' \
      "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz" \
      "https://mirrors.ustc.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz" \
      "https://mirrors.nju.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz"
    if [ -n "${OPENHOUSEAI_UBUNTU_ROOTFS_URLS:-}" ]; then
      printf '%s\n' "$OPENHOUSEAI_UBUNTU_ROOTFS_URLS"
    fi
  fi

  # Always retain Ubuntu's own cloud-image host as the authoritative source.
  printf '%s\n' \
    "https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-${arch}-root.tar.xz"

  if [ "$mode" != "cn" ]; then
    printf '%s\n' \
      "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz" \
      "https://mirrors.ustc.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-${arch}-root.tar.xz"
  fi

  printf '%s\n' \
    "https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-${arch}-root.tar.xz"
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
  printf '%s\n' "${OPENHOUSEAI_UBUNTU_ROOTFS_MIN_BYTES:-52428800}"
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
  UBUNTU_CURL_SOURCE_TIMEOUT="${OPENHOUSEAI_UBUNTU_ROOTFS_SOURCE_TIMEOUT:-$source_timeout}"
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
  local cache_root tmp_dir archive partial headers listing url normalized_url expected_size min_bytes

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
  done < <(ubuntu_rootfs_candidates | awk 'NF && !seen[$0]++')

  cleanup_failed_ubuntu_install
  log "所有 Ubuntu rootfs 来源均下载或安装失败。"
  return 1
)

install_ubuntu_env_probe_cli() {
  if proot-distro login ubuntu -- bash -lc 'test -x "$HOME/bin/openhouseai-env-probe"' >/dev/null 2>&1; then
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
  printf 'OPENHOUSEAI_INSTALL_SIDE=%s\n' "$INSTALL_SIDE"
  printf 'OPENHOUSEAI_RUNTIME=%s\n' "$(detect_runtime)"
  printf 'OPENHOUSEAI_UBUNTU_ROOTFS=%s\n' "$(detect_ubuntu_rootfs)"
}

main "$@"
EOF
  chmod 755 "$UBUNTU_PROBE_STAGING"
  local probe_staging_q
  probe_staging_q="$(shell_quote "$UBUNTU_PROBE_STAGING")"
  run_logged proot-distro login ubuntu -- bash -lc "mkdir -p \"\$HOME/bin\"; cp $probe_staging_q \"\$HOME/bin/openhouseai-env-probe\"; chmod 755 \"\$HOME/bin/openhouseai-env-probe\"; \"\$HOME/bin/openhouseai-env-probe\""
  rm -f "$UBUNTU_PROBE_STAGING"
  log "已注入 Ubuntu 侧环境探测 CLI：~/bin/openhouseai-env-probe"
}

if ! command -v proot-distro >/dev/null 2>&1; then
  log "缺少 proot-distro，请先执行“更新 Termux 软件包”。"
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

if proot-distro login ubuntu -- true >/dev/null 2>&1; then
  if [ "$ubuntu_was_present" -eq 1 ]; then
    log "Ubuntu rootfs 已可登录。"
  else
    log "Ubuntu 安装完成。"
  fi
else
  log "Ubuntu 安装后未生成可用的 rootfs。"
  exit 1
fi
