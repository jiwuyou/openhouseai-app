require_ubuntu

log "正在 Ubuntu 内安装或检查 Node.js 24 LTS。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"

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

node_major() {
  node -p "process.versions.node.split(\".\")[0]" 2>/dev/null || printf 0
}

if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
  major="$(node_major)"
  if [ "${major:-0}" -ge 24 ]; then
    echo "Node.js 已满足要求：$(node -v)"
    echo "npm：$(npm -v)"
    mkdir -p "$HOME/.npm-global/bin"
    npm config set prefix "$HOME/.npm-global"
    exit 0
  fi
  echo "当前 Node.js 版本过旧：$(node -v)，将安装 Node.js 24 LTS。"
fi

NODE_DIST_BASE="${OPENHOUSEAI_NODE_DIST_BASE:-https://nodejs.org/dist/latest-v24.x}"
NODE_ROOT="$HOME/.local/node"
NODE_TMP="$HOME/.local/node-download"
mkdir -p "$NODE_TMP" "$HOME/.local"

echo "正在安装 Node.js 24 LTS 到 $NODE_ROOT"
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

export PATH="$NODE_ROOT/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"
mkdir -p "$HOME/.npm-global/bin"
npm config set prefix "$HOME/.npm-global"
npm config set registry "${NPM_REGISTRY:-https://registry.npmjs.org/}"
node -v
npm -v

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

log "Node.js 24 LTS 安装阶段已完成。"
