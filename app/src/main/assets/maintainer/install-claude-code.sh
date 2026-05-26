require_ubuntu

log "正在 Ubuntu 内安装或检查 Claude Code。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export PATH="$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"
if command -v claude >/dev/null 2>&1; then
  echo "Claude Code 已安装：$(command -v claude)"
  claude --version || true
  exit 0
fi
if ! command -v npm >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  apt update
  apt install -y nodejs npm
fi
mkdir -p "$HOME/.npm-global/bin"
npm config set prefix "$HOME/.npm-global"
npm install -g @anthropic-ai/claude-code
export PATH="$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"
command -v claude
claude --version || true
PATH_LINE="export PATH=\"\$HOME/.opencode/bin:\$HOME/.local/bin:\$HOME/.npm-global/bin:\$PATH\""
for PROFILE_FILE in "$HOME/.profile" "$HOME/.bashrc"; do
  touch "$PROFILE_FILE"
  if ! grep -Fq "$PATH_LINE" "$PROFILE_FILE"; then
    {
      printf "\n# OpenHouse agent tools\n"
      printf "%s\n" "$PATH_LINE"
    } >> "$PROFILE_FILE"
  fi
done'

log "Claude Code 安装阶段已完成。"
