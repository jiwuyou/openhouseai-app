require_ubuntu

log "正在 Ubuntu 内安装或检查 Reasonix。"
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

ensure_node_npm() {
  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    major="$(node -p "process.versions.node.split(\".\")[0]" 2>/dev/null || printf 0)"
    if [ "${major:-0}" -ge 22 ]; then
      return 0
    fi
  fi

  NODE_DIST_BASE="${OPENHOUSEAI_NODE_DIST_BASE:-https://nodejs.org/dist/latest-v22.x}"
  NODE_ROOT="$HOME/.local/node"
  NODE_TMP="$HOME/.local/node-download"
  mkdir -p "$NODE_TMP" "$HOME/.local"

  echo "正在安装 Node.js 22 到 $NODE_ROOT"
  NODE_TARBALL="$(curl -fsSL --connect-timeout 20 --retry 3 --retry-delay 2 --retry-all-errors "$NODE_DIST_BASE/SHASUMS256.txt" | awk "/linux-arm64.tar.gz\$/ { print \$2; exit }")"
  if [ -z "$NODE_TARBALL" ]; then
    echo "未能从 $NODE_DIST_BASE 找到 linux-arm64 Node.js 包。" >&2
    exit 5
  fi

  download_with_retry "$NODE_DIST_BASE/$NODE_TARBALL" "$NODE_TMP/$NODE_TARBALL"
  rm -rf "$NODE_ROOT"
  mkdir -p "$NODE_ROOT"
  tar -xzf "$NODE_TMP/$NODE_TARBALL" -C "$NODE_ROOT" --strip-components=1
  rm -f "$NODE_TMP/$NODE_TARBALL"

  export PATH="$NODE_ROOT/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:/usr/local/bin:$PATH"
  node -v
  npm -v
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

ensure_node_npm

if command -v reasonix >/dev/null 2>&1; then
  echo "Reasonix 已安装：$(command -v reasonix)"
  reasonix --version || true
else
  mkdir -p "$HOME/.npm-global/bin"
  configure_npm_network
  install_npm_global reasonix
fi

export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:/usr/local/bin:$PATH"
command -v reasonix
reasonix --version || true

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

log "Reasonix 安装阶段已完成。"
