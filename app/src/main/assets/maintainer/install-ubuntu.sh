TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
UBUNTU_PROBE_STAGING="$TERMUX_HOME/.maintainer-logs/openhouseai-env-probe-ubuntu.sh"

ubuntu_rootfs_candidates() {
  if [ -n "${OPENHOUSEAI_UBUNTU_ROOTFS_URL:-}" ]; then
    printf '%s\n' "$OPENHOUSEAI_UBUNTU_ROOTFS_URL"
    return 0
  fi

  cat <<'EOF'
https://mirrors.ustc.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://cloud-images.ubuntu.com/jammy/current/jammy-server-cloudimg-arm64-root.tar.xz
EOF
}

select_ubuntu_rootfs_url() {
  local best_url="" best_speed=0 url metrics code speed size
  SELECTED_UBUNTU_ROOTFS_URL=""

  if ! command -v curl >/dev/null 2>&1; then
    log "缺少 curl，无法测速 Ubuntu rootfs 下载源。"
    return 1
  fi

  log "正在测速 Ubuntu rootfs 下载源。"
  while IFS= read -r url; do
    [ -n "$url" ] || continue
    log "测速：$url"
    metrics="$(
      curl -L --connect-timeout 8 --max-time 20 -r 0-1048575 \
        -o /dev/null \
        -w 'code=%{http_code} speed=%{speed_download} size=%{size_download}' \
        "$url" 2>/dev/null || true
    )"
    code="$(printf '%s\n' "$metrics" | awk '{for (i=1;i<=NF;i++) if ($i ~ /^code=/) {sub(/^code=/,"",$i); print $i; exit}}')"
    speed="$(printf '%s\n' "$metrics" | awk '{for (i=1;i<=NF;i++) if ($i ~ /^speed=/) {sub(/^speed=/,"",$i); printf "%.0f\n", $i; exit}}')"
    size="$(printf '%s\n' "$metrics" | awk '{for (i=1;i<=NF;i++) if ($i ~ /^size=/) {sub(/^size=/,"",$i); printf "%.0f\n", $i; exit}}')"
    speed="${speed:-0}"
    size="${size:-0}"
    if { [ "$code" = "200" ] || [ "$code" = "206" ]; } && [ "$size" -gt 0 ] && [ "$speed" -gt "$best_speed" ]; then
      best_url="$url"
      best_speed="$speed"
      log "当前最快：$best_url (${best_speed} B/s)"
    fi
  done <<EOF
$(ubuntu_rootfs_candidates)
EOF

  if [ -z "$best_url" ]; then
    log "未找到可用的 Ubuntu rootfs 下载源。"
    return 1
  fi

  SELECTED_UBUNTU_ROOTFS_URL="$best_url"
}

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
  run_logged proot-distro login ubuntu -- bash -lc 'mkdir -p "$HOME/bin"; cp "/data/data/com.termux/files/home/.maintainer-logs/openhouseai-env-probe-ubuntu.sh" "$HOME/bin/openhouseai-env-probe"; chmod 755 "$HOME/bin/openhouseai-env-probe"; "$HOME/bin/openhouseai-env-probe"'
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
  select_ubuntu_rootfs_url
  ubuntu_rootfs_url="$SELECTED_UBUNTU_ROOTFS_URL"
  log "正在执行 proot-distro install -n ubuntu $ubuntu_rootfs_url"
  run_logged proot-distro install -n ubuntu "$ubuntu_rootfs_url"
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
