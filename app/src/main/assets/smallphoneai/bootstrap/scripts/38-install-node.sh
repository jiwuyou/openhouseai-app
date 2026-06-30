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

log "正在 Ubuntu 内安装或检查 Node.js 24 LTS。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"

download_with_retry() {
  local url="$1"
  local output="$2"
  local attempt
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

NODE_DIST_BASE="${SMALLPHONEAI_NODE_DIST_BASE:-https://nodejs.org/dist/latest-v24.x}"
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

PATH_LINE="export PATH=\"\$HOME/.local/node/bin:\$HOME/.local/bin:\$HOME/.npm-global/bin:/usr/local/bin:\$PATH\""
for PROFILE_FILE in "$HOME/.profile" "$HOME/.bashrc"; do
  touch "$PROFILE_FILE"
  if ! grep -Fq "$PATH_LINE" "$PROFILE_FILE"; then
    {
      printf "\n# SmallPhoneAI agent tools\n"
      printf "%s\n" "$PATH_LINE"
    } >> "$PROFILE_FILE"
  fi
done'

log "Node.js 24 LTS 安装阶段完成。"
