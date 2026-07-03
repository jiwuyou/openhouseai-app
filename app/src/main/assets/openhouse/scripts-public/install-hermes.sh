#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_openhouse-postinstall-common.sh
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"

HERMES_REPO_URL="$(oh_maybe_rewrite_github_url "${OPENHOUSE_HERMES_REPO_URL:-https://github.com/nesquena/hermes-webui.git}")"
HERMES_DIR="${OPENHOUSE_HERMES_DIR:-/root/.local/share/openhouseai/hermes-webui}"

oh_log "开始准备 Hermes WebUI 可选安装环境。"
oh_run_ubuntu_bash "$(cat <<EOF
set -euo pipefail
export PATH="\$HOME/.local/bin:/usr/local/bin:\$PATH"
command -v git >/dev/null 2>&1 || { echo "缺少 git，请先安装 Ubuntu 基础包。" >&2; exit 3; }
command -v curl >/dev/null 2>&1 || { echo "缺少 curl，请先安装 Ubuntu 基础包。" >&2; exit 3; }
command -v python3 >/dev/null 2>&1 || { echo "缺少 python3，请先安装 Ubuntu 基础包。" >&2; exit 3; }

if ! command -v uv >/dev/null 2>&1; then
  echo "正在安装 uv 到用户目录。"
  curl -LsSf https://astral.sh/uv/install.sh | sh
fi

export PATH="\$HOME/.local/bin:\$PATH"
mkdir -p "$(dirname "$HERMES_DIR")"
if [ -d "$HERMES_DIR/.git" ]; then
  cd "$HERMES_DIR"
  git fetch --all --prune || true
  git pull --ff-only || true
else
  rm -rf "$HERMES_DIR"
  git clone "$HERMES_REPO_URL" "$HERMES_DIR"
  cd "$HERMES_DIR"
fi

uv venv .venv
. .venv/bin/activate
if [ -f pyproject.toml ]; then
  uv pip install -e . || true
elif [ -f requirements.txt ]; then
  uv pip install -r requirements.txt || true
fi

printf 'Hermes 目录：%s\n' "$HERMES_DIR"
printf 'uv 环境：%s\n' "$HERMES_DIR/.venv"
EOF
)"

cat <<EOF

Hermes WebUI 可选安装环境已准备。
这一步只准备独立目录和 uv 环境，不假设上游当前启动命令固定。

请让 pi-agent 继续阅读：
- /root/openhouse/docs/HERMES_SETUP.md
- /root/openhouse/docs/SERVICE_MANAGER.md
- /root/openhouse/docs/GITHUB_NETWORK_MIRRORS.md

然后按上游 README 确认启动命令，前台测通后再注册 service-manager 和侧边栏。
Hermes 目录：$HERMES_DIR
EOF
oh_next_docs
