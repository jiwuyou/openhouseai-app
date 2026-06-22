#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

run_logged() {
  log "+ $*"
  "$@"
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

run_ubuntu_logged() {
  if is_current_ubuntu; then
    run_logged "$@"
  else
    run_logged proot-distro login ubuntu -- "$@"
  fi
}

if ! is_current_ubuntu && { ! command -v proot-distro >/dev/null 2>&1 || ! proot-distro login ubuntu -- true >/dev/null 2>&1; }; then
  log "Ubuntu 不可用，请先运行：bash bootstrap.sh ubuntu"
  exit 2
fi

log "正在 Ubuntu 内安装或检查 ClaudeCodeUI / CloudCLI。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:/usr/local/bin:$PATH"

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  echo "Node.js 尚未安装，请先执行 Node.js 安装阶段。" >&2
  exit 3
fi

major="$(node -p "process.versions.node.split(\".\")[0]" 2>/dev/null || printf 0)"
if [ "${major:-0}" -lt 24 ]; then
  echo "Node.js 版本过旧：$(node -v)，请先执行 Node.js 安装阶段。" >&2
  exit 4
fi

export DEBIAN_FRONTEND=noninteractive
if command -v apt >/dev/null 2>&1; then
  dpkg --configure -a
  apt -f install -y
  apt install -y python3 make g++ build-essential
fi

mkdir -p "$HOME/.npm-global/bin" "$HOME/.cloudcli" "$HOME/.config/openhouseai" "$HOME/workspace"
npm config set prefix "$HOME/.npm-global"
npm config set registry "${NPM_REGISTRY:-https://registry.npmjs.org/}"
npm config set fetch-retries "${SMALLPHONEAI_NPM_FETCH_RETRIES:-5}"
npm config set fetch-retry-mintimeout "${SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT:-20000}"
npm config set fetch-retry-maxtimeout "${SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT:-120000}"
npm config set fetch-timeout "${SMALLPHONEAI_NPM_FETCH_TIMEOUT:-600000}"

install_timeout="${SMALLPHONEAI_NPM_INSTALL_TIMEOUT:-7200s}"
if command -v cloudcli >/dev/null 2>&1; then
  echo "CloudCLI 已安装：$(command -v cloudcli)"
  cloudcli version || cloudcli --version || true
else
  for attempt in 1 2 3; do
    echo "正在安装 @cloudcli-ai/cloudcli（第 $attempt 次，最长等待 $install_timeout）"
    if command -v timeout >/dev/null 2>&1; then
      if timeout -k 30s "$install_timeout" npm install -g @cloudcli-ai/cloudcli --no-audit --no-fund --loglevel=verbose; then
        break
      fi
    elif npm install -g @cloudcli-ai/cloudcli --no-audit --no-fund --loglevel=verbose; then
      break
    fi
    if [ "$attempt" -eq 3 ]; then
      echo "@cloudcli-ai/cloudcli 安装失败，请检查网络或 npm registry。" >&2
      exit 1
    fi
    sleep $((attempt * 10))
  done
fi

export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:/usr/local/bin:$PATH"
command -v cloudcli
cloudcli version || cloudcli --version || true

printf "%s\n" "23083" > "$HOME/.config/openhouseai/claude-code-ui-port"
printf "%s\n" "http://127.0.0.1:23083" > "$HOME/.config/openhouseai/claude-code-ui-url"

PATH_LINE="export PATH=\"\$HOME/.local/node/bin:\$HOME/.opencode/bin:\$HOME/.local/bin:\$HOME/.npm-global/bin:/usr/local/bin:\$PATH\""
for PROFILE_FILE in "$HOME/.profile" "$HOME/.bashrc"; do
  touch "$PROFILE_FILE"
  if ! grep -Fq "$PATH_LINE" "$PROFILE_FILE"; then
    {
      printf "\n# SmallPhoneAI agent tools\n"
      printf "%s\n" "$PATH_LINE"
    } >> "$PROFILE_FILE"
  fi
done'

log "ClaudeCodeUI / CloudCLI 安装阶段完成。"
