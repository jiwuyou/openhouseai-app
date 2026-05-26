TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
UBUNTU_PROBE_STAGING="$TERMUX_HOME/.maintainer-logs/openhouseai-env-probe-ubuntu.sh"

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
  log "正在执行 proot-distro install ubuntu"
  run_logged proot-distro install ubuntu
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
