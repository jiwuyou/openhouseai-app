require_ubuntu

log "正在 Ubuntu 内安装或检查 Node.js 24 LTS。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export HOME=/root
NODE_ROOT=/root/.local/node
NPM_GLOBAL_ROOT=/root/.npm-global
GUEST_PATH="$NODE_ROOT/bin:$NPM_GLOBAL_ROOT/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
INHERITED_NODE="$(command -v node 2>/dev/null || true)"
case "$INHERITED_NODE" in
  /data/data/com.termux/*)
    echo "忽略从 Termux 继承的 Node.js：$INHERITED_NODE"
    ;;
esac
unset PREFIX LD_LIBRARY_PATH LD_PRELOAD
export PATH="$GUEST_PATH"

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

valid_ubuntu_node() {
  local node_path major platform_arch
  node_path="$(command -v node 2>/dev/null || true)"
  [ "$node_path" = "$NODE_ROOT/bin/node" ] || return 1
  [ -x "$NODE_ROOT/bin/node" ] || return 1
  command -v npm >/dev/null 2>&1 || return 1
  major="$(node -p "process.versions.node.split(\".\")[0]" 2>/dev/null || printf 0)"
  platform_arch="$(node -p "process.platform+\"/\"+process.arch" 2>/dev/null || true)"
  [ "${major:-0}" -ge 24 ] && [ "$platform_arch" = "linux/arm64" ]
}

if valid_ubuntu_node; then
  echo "Ubuntu Node.js 已满足要求：$NODE_ROOT/bin/node $(node -v) ($(node -p "process.platform+\"/\"+process.arch"))"
  echo "npm：$(npm -v)"
  mkdir -p "$NPM_GLOBAL_ROOT/bin"
  npm config set prefix "$NPM_GLOBAL_ROOT"
  exit 0
fi

NODE_DIST_BASE="${OPENHOUSEAI_NODE_DIST_BASE:-${SMALLPHONEAI_NODE_DIST_BASE:-https://nodejs.org/dist/latest-v24.x}}"
NODE_TMP=/root/.local/node-download
mkdir -p "$NODE_TMP" /root/.local

echo "正在安装 Node.js 24 LTS 到 $NODE_ROOT"
NODE_SHASUMS="$NODE_TMP/SHASUMS256.txt"
curl -fsSL --connect-timeout 20 --retry 3 --retry-delay 2 --retry-all-errors "$NODE_DIST_BASE/SHASUMS256.txt" -o "$NODE_SHASUMS"
NODE_TARBALL="$(awk "/linux-arm64.tar.gz\$/ { print \$2; exit }" "$NODE_SHASUMS")"
if [ -z "$NODE_TARBALL" ]; then
  echo "未能从 $NODE_DIST_BASE 找到 linux-arm64 Node.js 包。" >&2
  exit 5
fi

download_with_retry "$NODE_DIST_BASE/$NODE_TARBALL" "$NODE_TMP/$NODE_TARBALL"
(
  cd "$NODE_TMP"
  grep -F " $NODE_TARBALL" "$NODE_SHASUMS" | sha256sum -c -
)
rm -rf "$NODE_ROOT"
mkdir -p "$NODE_ROOT"
tar -xzf "$NODE_TMP/$NODE_TARBALL" -C "$NODE_ROOT" --strip-components=1
rm -f "$NODE_TMP/$NODE_TARBALL"

export PATH="$GUEST_PATH"
mkdir -p "$NPM_GLOBAL_ROOT/bin"
npm config set prefix "$NPM_GLOBAL_ROOT"
npm config set registry "${NPM_REGISTRY:-https://registry.npmjs.org/}"
if ! valid_ubuntu_node; then
  echo "Ubuntu Node.js 安装校验失败；要求 $NODE_ROOT/bin/node 且运行时为 linux/arm64。" >&2
  exit 6
fi
echo "Ubuntu Node.js 安装完成：$(command -v node) $(node -v) ($(node -p "process.platform+\"/\"+process.arch"))"
echo "npm：$(npm -v)"

PATH_LINE="export PATH=\"/root/.local/node/bin:/root/.npm-global/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\""
for PROFILE_FILE in /root/.profile /root/.bashrc; do
  touch "$PROFILE_FILE"
  if ! grep -Fq "$PATH_LINE" "$PROFILE_FILE"; then
    {
      printf "\n# OpenHouseAI agent tools\n"
      printf "%s\n" "$PATH_LINE"
    } >> "$PROFILE_FILE"
  fi
done'

log "Node.js 24 LTS 安装阶段已完成。"
