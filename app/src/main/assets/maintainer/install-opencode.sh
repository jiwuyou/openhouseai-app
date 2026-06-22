require_ubuntu

log "正在 Ubuntu 内通过 npm 安装或检查 OpenCode。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:$PATH"

if command -v opencode >/dev/null 2>&1; then
  echo "OpenCode 已安装：$(command -v opencode)"
  opencode --version || true
  exit 0
fi

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  echo "Node.js 尚未安装，请先执行 Node.js 24 LTS 安装阶段。" >&2
  exit 3
fi

node_major="$(node -p "process.versions.node.split(\".\")[0]" 2>/dev/null || printf 0)"
if [ "${node_major:-0}" -lt 24 ]; then
  echo "Node.js 版本过旧：$(node -v)，请先执行 Node.js 24 LTS 安装阶段。" >&2
  exit 4
fi

mkdir -p "$HOME/.npm-global/bin"
npm config set prefix "$HOME/.npm-global"
npm config set registry "${NPM_REGISTRY:-https://registry.npmjs.org/}"
npm config set fetch-retries "${OPENHOUSEAI_NPM_FETCH_RETRIES:-5}"
npm config set fetch-retry-mintimeout "${OPENHOUSEAI_NPM_FETCH_RETRY_MINTIMEOUT:-20000}"
npm config set fetch-retry-maxtimeout "${OPENHOUSEAI_NPM_FETCH_RETRY_MAXTIMEOUT:-120000}"
npm config set fetch-timeout "${OPENHOUSEAI_NPM_FETCH_TIMEOUT:-600000}"

install_timeout="${OPENHOUSEAI_NPM_INSTALL_TIMEOUT:-7200s}"
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
      printf "\n# OpenHouseAI agent tools\n"
      printf "%s\n" "$PATH_LINE"
    } >> "$PROFILE_FILE"
  fi
done'

log "正在 Ubuntu 主目录内写入产品路径辅助文件"
run_ubuntu_logged bash -lc 'set -euo pipefail; mkdir -p "$HOME/openhouseai-links"; printf "%s\n" "/data/data/com.termux/files/home/openhouseai-docs" > "$HOME/openhouseai-links/docs-path.txt"; printf "%s\n" "/data/data/com.termux/files/home/workspace" > "$HOME/openhouseai-links/workspace-path.txt"; echo "文档路径：$(cat "$HOME/openhouseai-links/docs-path.txt")"; echo "工作区路径：$(cat "$HOME/openhouseai-links/workspace-path.txt")"'

log "OpenCode 安装阶段已完成。"
