#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_openhouse-postinstall-common.sh
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"

missing=0

check_in_ubuntu() {
  local label="$1"
  local command_text="$2"
  if oh_run_ubuntu_bash "$command_text" >/tmp/openhouse-check-output.$$ 2>/tmp/openhouse-check-error.$$; then
    printf '[ok] %s\n' "$label"
    sed 's/^/  /' /tmp/openhouse-check-output.$$ || true
  else
    printf '[missing] %s\n' "$label"
    sed 's/^/  /' /tmp/openhouse-check-error.$$ >&2 || true
    missing=1
  fi
  rm -f /tmp/openhouse-check-output.$$ /tmp/openhouse-check-error.$$
}

printf 'OpenHouse AI 工具检查\n'
printf '======================\n'

check_in_ubuntu "Node.js" 'export PATH="$HOME/.local/node/bin:$PATH"; command -v node && node -v'
check_in_ubuntu "npm" 'export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$PATH"; command -v npm && npm -v'
check_in_ubuntu "Codex CLI" 'export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"; command -v codex && codex --version'
check_in_ubuntu "Claude Code" 'export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"; command -v claude && claude --version'
check_in_ubuntu "Claude Code native path for CloudCLI" 'test -x "$HOME/.local/bin/claude" && "$HOME/.local/bin/claude" --version'
check_in_ubuntu "CloudCLI" 'export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"; command -v cloudcli && (cloudcli version || cloudcli --version)'
check_in_ubuntu "cc-switch" 'export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:/usr/local/bin:$PATH"; command -v cc-switch && cc-switch --version'
check_in_ubuntu "OpenHouse docs" 'test -d /root/openhouse/docs && ls /root/openhouse/docs/START_HERE.md /root/openhouse/docs/SERVICE_MANAGER.md >/dev/null && printf "%s\n" /root/openhouse/docs'
check_in_ubuntu "OpenHouse scripts" 'test -d /root/openhouse/scripts && ls /root/openhouse/scripts/install-codex.sh /root/openhouse/scripts/check-ai-tools.sh >/dev/null && printf "%s\n" /root/openhouse/scripts'

if oh_run_bootstrap status; then
  printf '[ok] bootstrap status\n'
else
  printf '[warn] bootstrap status failed; service-manager 或上层服务可能需要修复。\n' >&2
  missing=1
fi

cat <<'EOF'

缺什么就补什么：
- Codex 缺失：/root/openhouse/scripts/install-codex.sh
- Claude Code 或 /root/.local/bin/claude 缺失：/root/openhouse/scripts/install-claude-code.sh
- CloudCLI 缺失：/root/openhouse/scripts/install-cloudcli.sh
- cc-switch 缺失：/root/openhouse/scripts/install-cc-switch.sh
- Hermes：可选高级能力，使用 /root/openhouse/scripts/install-hermes.sh 准备环境后继续按文档注册。
EOF

exit "$missing"
