require_ubuntu

log "正在 Ubuntu 内安装或检查 ClaudeCodeUI / CloudCLI。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:/usr/local/bin:$PATH"

download_with_retry() {
  url="$1"
  output="$2"
  for attempt in 1 2 3 4 5; do
    echo "下载：$url（第 $attempt 次）"
    if curl -fL \
      --connect-timeout 20 \
      --retry 3 \
      --retry-delay 2 \
      --retry-all-errors \
      --speed-limit 1024 \
      --speed-time 300 \
      "$url" -o "$output"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

require_node_24() {
  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    major="$(node -p "process.versions.node.split(\".\")[0]" 2>/dev/null || printf 0)"
    if [ "${major:-0}" -ge 24 ]; then
      return 0
    fi
    echo "Node.js 版本过旧：$(node -v)，请先执行 Node.js 24 LTS 安装阶段。" >&2
    exit 4
  fi

  echo "Node.js 尚未安装，请先执行 Node.js 24 LTS 安装阶段。" >&2
  exit 3
}

configure_npm_network() {
  npm config set prefix "$HOME/.npm-global"
  npm config set registry "${NPM_REGISTRY:-https://registry.npmjs.org/}"
  npm config set fetch-retries "${OPENHOUSEAI_NPM_FETCH_RETRIES:-5}"
  npm config set fetch-retry-mintimeout "${OPENHOUSEAI_NPM_FETCH_RETRY_MINTIMEOUT:-20000}"
  npm config set fetch-retry-maxtimeout "${OPENHOUSEAI_NPM_FETCH_RETRY_MAXTIMEOUT:-120000}"
  npm config set fetch-timeout "${OPENHOUSEAI_NPM_FETCH_TIMEOUT:-600000}"
}

install_npm_global() {
  package_name="$1"
  install_timeout="${OPENHOUSEAI_NPM_INSTALL_TIMEOUT:-7200s}"
  for attempt in 1 2 3; do
    echo "正在安装 $package_name（第 $attempt 次，最长等待 $install_timeout）"
    if command -v timeout >/dev/null 2>&1; then
      if timeout -k 30s "$install_timeout" npm install -g "$package_name" --no-audit --no-fund --loglevel=verbose; then
        return 0
      fi
    elif npm install -g "$package_name" --no-audit --no-fund --loglevel=verbose; then
      return 0
    fi
    echo "$package_name 安装失败或超时，准备重试。"
    sleep $((attempt * 10))
  done
  echo "$package_name 安装失败，请检查网络或 npm registry。" >&2
  return 1
}

ensure_build_tools() {
  export DEBIAN_FRONTEND=noninteractive
  if command -v apt >/dev/null 2>&1; then
    dpkg --configure -a
    apt -f install -y
    apt install -y python3 make g++ build-essential
  fi
}

require_node_24
ensure_build_tools
mkdir -p "$HOME/.npm-global/bin" "$HOME/.cloudcli" "$HOME/.config/openhouseai" "$HOME/workspace"
configure_npm_network

if command -v cloudcli >/dev/null 2>&1; then
  echo "CloudCLI 已安装：$(command -v cloudcli)"
  cloudcli version || cloudcli --version || true
else
  install_npm_global @cloudcli-ai/cloudcli
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
      printf "\n# OpenHouseAI agent tools\n"
      printf "%s\n" "$PATH_LINE"
    } >> "$PROFILE_FILE"
  fi
done'

log "ClaudeCodeUI / CloudCLI 安装阶段已完成。"
