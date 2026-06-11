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

run_ubuntu_logged() {
  if is_current_ubuntu; then
    run_logged "$@"
  else
    run_logged proot-distro login ubuntu -- "$@"
  fi
}

run_environment_probe

if ! is_current_ubuntu && { ! command -v proot-distro >/dev/null 2>&1 || ! proot-distro login ubuntu -- true >/dev/null 2>&1; }; then
  log "Ubuntu 不可用，请先运行：bash bootstrap.sh ubuntu"
  exit 2
fi

log "正在 Ubuntu 内通过 npm 安装或检查 OpenCode。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:$PATH"

if command -v opencode >/dev/null 2>&1; then
  echo "OpenCode 已安装：$(command -v opencode)"
  opencode --version || true
  exit 0
fi

if ! command -v npm >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  echo "正在修复可能被中断的 Ubuntu 软件包状态"
  dpkg --configure -a
  apt -f install -y
  apt update
  apt install -y nodejs npm
fi

mkdir -p "$HOME/.npm-global/bin"
npm config set prefix "$HOME/.npm-global"
npm config set registry "${NPM_REGISTRY:-https://registry.npmjs.org/}"
npm config set fetch-retries "${SMALLPHONEAI_NPM_FETCH_RETRIES:-5}"
npm config set fetch-retry-mintimeout "${SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT:-20000}"
npm config set fetch-retry-maxtimeout "${SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT:-120000}"
npm config set fetch-timeout "${SMALLPHONEAI_NPM_FETCH_TIMEOUT:-600000}"

install_timeout="${SMALLPHONEAI_NPM_INSTALL_TIMEOUT:-7200s}"
for attempt in 1 2 3; do
  echo "正在安装 opencode-ai（第 $attempt 次，最长等待 $install_timeout）"
  if command -v timeout >/dev/null 2>&1; then
    if timeout -k 30s "$install_timeout" npm install -g opencode-ai --no-audit --no-fund --loglevel=verbose; then
      break
    fi
  elif npm install -g opencode-ai --no-audit --no-fund --loglevel=verbose; then
    break
  fi
  if [ "$attempt" -eq 3 ]; then
    echo "opencode-ai 安装失败，请检查网络或 npm registry。" >&2
    exit 1
  fi
  sleep $((attempt * 10))
done

export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:$PATH"
command -v opencode
opencode --version || true

PATH_LINE="export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\""
for PROFILE_FILE in "$HOME/.profile" "$HOME/.bashrc"; do
  touch "$PROFILE_FILE"
  if ! grep -Fq "$PATH_LINE" "$PROFILE_FILE"; then
    {
      printf "\n# SmallPhoneAI agent tools\n"
      printf "%s\n" "$PATH_LINE"
    } >> "$PROFILE_FILE"
  fi
done'

log "正在写入 Ubuntu 内的产品路径提示。"
run_ubuntu_logged bash -lc 'set -euo pipefail
mkdir -p "$HOME/smallphoneai-links" "$HOME/workspace"
printf "%s\n" "/data/data/com.termux/files/home/smallphoneai-docs" > "$HOME/smallphoneai-links/docs-path.txt"
printf "%s\n" "/data/data/com.termux/files/home/workspace" > "$HOME/smallphoneai-links/workspace-path.txt"
echo "文档路径：$(cat "$HOME/smallphoneai-links/docs-path.txt")"
echo "工作区路径：$(cat "$HOME/smallphoneai-links/workspace-path.txt")"'

log "OpenCode 安装阶段完成。"
