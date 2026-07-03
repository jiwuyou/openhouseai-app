#!/usr/bin/env bash
set -euo pipefail

oh_log() {
  printf '[OpenHouse postinstall] %s\n' "$*"
}

oh_warn() {
  printf '[OpenHouse postinstall] WARN: %s\n' "$*" >&2
}

oh_die() {
  printf '[OpenHouse postinstall] ERROR: %s\n' "$*" >&2
  exit 1
}

oh_is_current_ubuntu() {
  [ -r /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

oh_is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

oh_termux_home() {
  if oh_is_current_ubuntu; then
    printf '%s\n' "/data/data/com.termux/files/home"
  else
    printf '%s\n' "${HOME:-/data/data/com.termux/files/home}"
  fi
}

oh_bootstrap_path() {
  local termux_home candidate
  termux_home="$(oh_termux_home)"
  for candidate in \
    "${OPENHOUSE_BOOTSTRAP:-}" \
    "${SMALLPHONEAI_BOOTSTRAP:-}" \
    "$termux_home/.smallphoneai-bootstrap/bootstrap.sh" \
    "$HOME/.smallphoneai-bootstrap/bootstrap.sh"; do
    [ -n "$candidate" ] || continue
    [ -f "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  return 1
}

oh_require_bootstrap() {
  local bootstrap
  bootstrap="$(oh_bootstrap_path)" || oh_die "找不到 bootstrap.sh。请先完成 OpenHouse 首次安装，或设置 SMALLPHONEAI_BOOTSTRAP。"
  printf '%s\n' "$bootstrap"
}

oh_run_bootstrap() {
  local command_name="$1"
  local bootstrap
  bootstrap="$(oh_require_bootstrap)"
  chmod +x "$bootstrap" 2>/dev/null || true
  oh_log "执行 bootstrap 阶段：$command_name"
  bash "$bootstrap" "$command_name"
}

oh_run_ubuntu_bash() {
  local program="$1"
  if oh_is_current_ubuntu; then
    bash -lc "$program"
    return
  fi
  command -v proot-distro >/dev/null 2>&1 || oh_die "当前不在 Ubuntu，且 Termux 外层缺少 proot-distro。"
  proot-distro login ubuntu -- bash -lc "$program"
}

oh_print_tool_path() {
  local tool_name="$1"
  oh_run_ubuntu_bash "export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.local/bin:/usr/local/bin:\$PATH\"; command -v $tool_name"
}

oh_check_tool_version() {
  local tool_name="$1"
  local version_command="${2:-$1 --version}"
  oh_run_ubuntu_bash "set -e; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.local/bin:/usr/local/bin:\$PATH\"; command -v $tool_name; $version_command || true"
}

oh_ensure_claude_native_path() {
  oh_run_ubuntu_bash 'set -euo pipefail
export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"
mkdir -p "$HOME/.local/bin"

if [ -x "$HOME/.local/bin/claude" ] && "$HOME/.local/bin/claude" --version >/dev/null 2>&1; then
  printf "%s\n" "$HOME/.local/bin/claude"
  "$HOME/.local/bin/claude" --version || true
  exit 0
fi

rm -f "$HOME/.local/bin/claude"
candidate=""
for path in \
  "$HOME/.npm-global/bin/claude" \
  "$HOME/.local/node/bin/claude" \
  "/usr/local/bin/claude"; do
  if [ -x "$path" ]; then
    candidate="$path"
    break
  fi
done

if [ -z "$candidate" ]; then
  candidate="$(command -v claude 2>/dev/null || true)"
fi

if [ -z "$candidate" ]; then
  echo "Claude Code command not found. Run /root/openhouse/scripts/install-claude-code.sh first." >&2
  exit 1
fi

ln -sf "$candidate" "$HOME/.local/bin/claude"
printf "%s -> %s\n" "$HOME/.local/bin/claude" "$candidate"
"$HOME/.local/bin/claude" --version'
}

oh_next_docs() {
  cat <<'EOF'

下一步建议：
- 让 pi-agent 阅读 /root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md。
- 配置 Claude Code / CloudCLI 时阅读 /root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md。
- 配置模型迁移时阅读 /root/openhouse/docs/MODEL_API_SETUP.md。
- 服务启动、停止、注册和侧边栏入口阅读 /root/openhouse/docs/SERVICE_MANAGER.md。
- 出错时先运行 /root/openhouse/scripts/check-ai-tools.sh，再阅读 /root/openhouse/docs/RECOVERY.md。
EOF
}
