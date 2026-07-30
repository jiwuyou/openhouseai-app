#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
PATH="$PREFIX/bin:/system/bin"
REGION="auto"
TERMUX_REPO="auto"

log() {
  printf '[WuxianPi pre-tmux] %s\n' "$*"
}

usage() {
  printf 'Usage: wuxianpi-pre-tmux.sh [--region auto|cn|global] [--termux-repo URL|auto]\n'
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --region)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      REGION="$2"
      shift 2
      ;;
    --termux-repo)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      TERMUX_REPO="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$REGION" in
  auto|cn|global) ;;
  *) printf 'Unsupported region: %s\n' "$REGION" >&2; exit 2 ;;
esac

repo_candidates() {
  if [ "$TERMUX_REPO" != auto ] && [ -n "$TERMUX_REPO" ]; then
    printf '%s\n' "$TERMUX_REPO"
  fi
  case "$REGION" in
    global)
      cat <<'EOF'
https://packages-cf.termux.dev/apt/termux-main
https://packages.termux.dev/apt/termux-main
https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main
https://mirrors.ustc.edu.cn/termux/apt/termux-main
EOF
      ;;
    cn)
      cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main
https://mirrors.ustc.edu.cn/termux/apt/termux-main
https://mirrors.bfsu.edu.cn/termux/apt/termux-main
https://mirrors.nju.edu.cn/termux/apt/termux-main
https://packages-cf.termux.dev/apt/termux-main
EOF
      ;;
    *)
      cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main
https://packages-cf.termux.dev/apt/termux-main
https://mirrors.ustc.edu.cn/termux/apt/termux-main
https://packages.termux.dev/apt/termux-main
EOF
      ;;
  esac
}

run_timeout() {
  local seconds="$1"
  shift
  if [ -x "$PREFIX/bin/timeout" ]; then
    "$PREFIX/bin/timeout" --foreground "$seconds" "$@"
  elif [ -x /system/bin/timeout ]; then
    /system/bin/timeout "$seconds" "$@"
  else
    "$@"
  fi
}

probe_repo() {
  local repo="$1" probe="$repo/dists/stable/InRelease" host port
  if [ -x "$PREFIX/bin/curl" ]; then
    run_timeout 15 "$PREFIX/bin/curl" -fsIL --connect-timeout 6 --max-time 12 "$probe" >/dev/null
    return
  fi
  if [ -x "$PREFIX/bin/wget" ]; then
    run_timeout 15 "$PREFIX/bin/wget" -q --spider "$probe"
    return
  fi
  host="${repo#*://}"
  host="${host%%/*}"
  host="${host%%:*}"
  case "$repo" in http://*) port=80 ;; *) port=443 ;; esac
  run_timeout 8 "$PREFIX/bin/bash" -c "</dev/tcp/$host/$port"
}

repair_packages() {
  if [ -x "$PREFIX/bin/dpkg" ]; then
    env DEBIAN_FRONTEND=noninteractive "$PREFIX/bin/dpkg" \
      --force-confdef --force-confold --configure -a || true
  fi
  if [ -x "$PREFIX/bin/apt" ]; then
    env DEBIAN_FRONTEND=noninteractive "$PREFIX/bin/apt" \
      -o Dpkg::Options::=--force-confdef \
      -o Dpkg::Options::=--force-confold \
      -f install -y || true
  fi
}

apt_update() {
  run_timeout 300 env DEBIAN_FRONTEND=noninteractive DEBIAN_PRIORITY=critical \
    "$PREFIX/bin/apt" \
    -o Dpkg::Options::=--force-confdef \
    -o Dpkg::Options::=--force-confold \
    -o Acquire::Retries=2 \
    -o Acquire::http::Timeout=30 \
    -o Acquire::https::Timeout=30 update
}

apt_install_tmux() {
  run_timeout 1200 env DEBIAN_FRONTEND=noninteractive DEBIAN_PRIORITY=critical \
    "$PREFIX/bin/apt" \
    -o Dpkg::Options::=--force-confdef \
    -o Dpkg::Options::=--force-confold \
    -o Acquire::Retries=2 install -y tmux
}

[ -x "$PREFIX/bin/bash" ] || { log 'Termux bootstrap 尚未完成。'; exit 1; }
[ -x "$PREFIX/bin/apt" ] || { log '缺少 apt，无法准备 tmux。'; exit 1; }
if [ -x "$PREFIX/bin/tmux" ]; then
  log 'tmux 已安装。'
  exit 0
fi

sources_file="$PREFIX/etc/apt/sources.list"
mkdir -p "$(dirname "$sources_file")"
repair_packages

selected=""
seen="|"
while IFS= read -r repo; do
  [ -n "$repo" ] || continue
  case "$seen" in *"|$repo|"*) continue ;; esac
  seen="$seen$repo|"
  log "测试 Termux 软件源：$repo"
  if ! probe_repo "$repo"; then
    log "软件源预检失败，尝试下一个：$repo"
    continue
  fi
  printf 'deb %s stable main\n' "$repo" > "$sources_file"
  if apt_update; then
    selected="$repo"
    break
  fi
  log "apt update 失败，修复包状态并切换软件源：$repo"
  repair_packages
done < <(repo_candidates)

[ -n "$selected" ] || { log '所有候选 Termux 软件源均不可用。'; exit 1; }
log "使用 Termux 软件源：$selected"
apt_install_tmux || {
  repair_packages
  apt_update
  apt_install_tmux
}
[ -x "$PREFIX/bin/tmux" ] || { log 'tmux 安装后仍不可用。'; exit 1; }
log 'tmux 已准备完成；后续命令应使用 termux_exec_command。'
