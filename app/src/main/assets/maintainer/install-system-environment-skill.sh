require_ubuntu

detect_openhouse_runtime() {
  if is_current_ubuntu; then
    printf 'ubuntu'
    return 0
  fi

  if [ -x "${PREFIX:-/data/data/com.termux/files/usr}/bin/openhouse-env-probe" ]; then
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/openhouse-env-probe" 2>/dev/null \
      | awk -F= '$1=="OPENHOUSE_RUNTIME"{print $2; found=1} END{if(!found) exit 1}' \
      && return 0
  fi

  if [ -n "${TERMUX_VERSION:-}" ] || [ "${PREFIX:-}" = "/data/data/com.termux/files/usr" ]; then
    printf 'termux'
    return 0
  fi

  printf 'unknown'
}

log "正在探测当前维护脚本运行环境。"
CURRENT_RUNTIME="$(detect_openhouse_runtime)"
log "当前运行环境：$CURRENT_RUNTIME"
if [ "$CURRENT_RUNTIME" = "ubuntu" ]; then
  log "已在 Ubuntu 内，将直接写入 Ubuntu 用户级 OpenCode skills。"
else
  log "当前不在 Ubuntu 内，将通过 proot-distro 进入 Ubuntu 后写入 OpenCode skills。"
fi

log "正在检查 OpenCode 是否已安装。"
run_ubuntu_logged bash -lc 'set -euo pipefail; export PATH="$HOME/.opencode/bin:$HOME/.local/bin:$PATH"; if command -v opencode >/dev/null 2>&1 || test -x "$HOME/.opencode/bin/opencode"; then echo "OpenCode 已安装。"; else echo "尚未安装 OpenCode，请先执行“安装 OpenCode”。" >&2; exit 4; fi'

log "正在写入 OpenCode 用户级 skill：系统环境说明与 Agent 安装配置"
run_ubuntu_logged bash -s <<'__OPENHOUSE_INSTALL_SYSTEM_ENV_SKILL__'
set -euo pipefail
SYSTEM_ENV_SKILL_TARGET_DIR="$HOME/.config/opencode/skills/system-environment-description"
AGENT_INSTALL_SKILL_TARGET_DIR="$HOME/.config/opencode/skills/install-ai-agents"

SKILL_TARGET_DIR="$SYSTEM_ENV_SKILL_TARGET_DIR"
mkdir -p "$SKILL_TARGET_DIR"
__BUNDLED_SYSTEM_ENV_SKILL__
echo "系统环境说明技能目录：$SKILL_TARGET_DIR"
echo "系统环境说明技能文件：$SKILL_TARGET_DIR/SKILL.md"

SKILL_TARGET_DIR="$AGENT_INSTALL_SKILL_TARGET_DIR"
mkdir -p "$SKILL_TARGET_DIR"
__BUNDLED_OPENCODE_AGENT_INSTALL_SKILL__
echo "Agent 安装配置技能目录：$SKILL_TARGET_DIR"
echo "Agent 安装配置技能文件：$SKILL_TARGET_DIR/SKILL.md"
__OPENHOUSE_INSTALL_SYSTEM_ENV_SKILL__

log "OpenCode 用户级 skill 写入阶段已完成。"
