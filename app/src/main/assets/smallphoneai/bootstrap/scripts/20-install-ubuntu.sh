#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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
OPENHOUSE_HOME_DIR="$TERMUX_HOME/openhouse"
TERMUX_WORKSPACE_DIR="$OPENHOUSE_HOME_DIR/workspace"
LEGACY_WORKSPACE_DIR="$TERMUX_HOME/workspace"

ubuntu_rootfs_candidates() {
  if [ -n "${SMALLPHONEAI_UBUNTU_ROOTFS_URL:-}" ]; then
    printf '%s\n' "$SMALLPHONEAI_UBUNTU_ROOTFS_URL"
    return 0
  fi
  if [ -n "${SMALLPHONEAI_UBUNTU_ROOTFS_URLS:-}" ]; then
    printf '%s\n' "$SMALLPHONEAI_UBUNTU_ROOTFS_URLS"
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

  if command -v smallphoneai_is_cn_retry >/dev/null 2>&1 && smallphoneai_is_cn_retry; then
    log "国内网络重试：按固定顺序探测 Ubuntu rootfs 下载源。"
    while IFS= read -r url; do
      [ -n "$url" ] || continue
      log "探测：$url"
      metrics="$(
        curl -L --connect-timeout 8 --max-time 20 -r 0-1048575 \
          -o /dev/null \
          -w 'code=%{http_code} size=%{size_download}' \
          "$url" 2>/dev/null || true
      )"
      code="$(printf '%s\n' "$metrics" | awk '{for (i=1;i<=NF;i++) if ($i ~ /^code=/) {sub(/^code=/,"",$i); print $i; exit}}')"
      size="$(printf '%s\n' "$metrics" | awk '{for (i=1;i<=NF;i++) if ($i ~ /^size=/) {sub(/^size=/,"",$i); printf "%.0f\n", $i; exit}}')"
      size="${size:-0}"
      if { [ "$code" = "200" ] || [ "$code" = "206" ]; } && [ "$size" -gt 0 ]; then
        SELECTED_UBUNTU_ROOTFS_URL="$url"
        log "选择 Ubuntu rootfs 国内固定源：$url"
        return 0
      fi
      log "Ubuntu rootfs 国内源不可用：$url"
    done <<EOF
$(ubuntu_rootfs_candidates)
EOF
    log "国内固定 Ubuntu rootfs 源均不可用。"
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
  run_logged proot-distro login ubuntu -- bash -lc 'mkdir -p "$HOME/bin"; cp "/data/data/com.termux/files/home/.smallphoneai-bootstrap/smallphoneai-env-probe-ubuntu.sh" "$HOME/bin/smallphoneai-env-probe"; chmod 755 "$HOME/bin/smallphoneai-env-probe"; "$HOME/bin/smallphoneai-env-probe"'
  rm -f "$UBUNTU_PROBE_STAGING"
  log "已注入 Ubuntu 侧环境探测 CLI：~/bin/smallphoneai-env-probe"
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
  run_logged proot-distro login ubuntu -- bash "/data/data/com.termux/files/home/.smallphoneai-bootstrap/ensure-openhouse-workspace-ubuntu.sh"
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
  select_ubuntu_rootfs_url
  ubuntu_rootfs_url="$SELECTED_UBUNTU_ROOTFS_URL"
  log "正在安装 Ubuntu rootfs：$ubuntu_rootfs_url"
  run_logged proot-distro install -n ubuntu "$ubuntu_rootfs_url"
fi

install_ubuntu_env_probe_cli
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
