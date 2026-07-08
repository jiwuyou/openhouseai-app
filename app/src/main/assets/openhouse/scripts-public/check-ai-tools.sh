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

check_bootstrap_health_signatures() {
  if oh_run_ubuntu_bash '
set -euo pipefail

check_status=0
signature_dir=""
for candidate in \
  "${OPENHOUSE_HEALTH_SIGNATURE_DIR:-}" \
  "${SMALLPHONEAI_HEALTH_SIGNATURE_DIR:-}" \
  "${HOME:+$HOME/.openhouseai/health-signatures}" \
  "${HOME:+$HOME/.smallphoneai/health-signatures}" \
  "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.openhouseai/health-signatures}" \
  "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.smallphoneai/health-signatures}"; do
  [ -n "$candidate" ] || continue
  if [ -d "$candidate" ]; then
    signature_dir="$candidate"
    break
  fi
done
[ -n "$signature_dir" ] || signature_dir="${HOME:-/root}/.openhouseai/health-signatures"

find_signature_file() {
  local stage="$1"
  local candidate
  case "$stage" in
    1)
      for candidate in \
        "$signature_dir/bootstrap-stage-1.json" \
        "$signature_dir/stage1-bootstrap-ai.json" \
        "$signature_dir/phase1-bootstrap-ai.json" \
        "$signature_dir/first-stage-bootstrap-ai.json" \
        "$signature_dir/stage-one-bootstrap-ai.json"; do
        [ -f "$candidate" ] && { printf "%s\n" "$candidate"; return 0; }
      done
      ;;
    2)
      for candidate in \
        "$signature_dir/bootstrap-stage-2.json" \
        "$signature_dir/stage2-bootstrap-ai.json" \
        "$signature_dir/phase2-bootstrap-ai.json" \
        "$signature_dir/second-stage-bootstrap-ai.json" \
        "$signature_dir/stage-two-bootstrap-ai.json"; do
        [ -f "$candidate" ] && { printf "%s\n" "$candidate"; return 0; }
      done
      ;;
  esac
  return 1
}

read_json_key() {
  local file="$1"
  local key="$2"
  [ -f "$file" ] || return 1
  sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$file" | head -n 1
}

read_signer() {
  local file="$1"
  local key value
  for key in signerId signedBy signer ai agent model; do
    value="$(read_json_key "$file" "$key" || true)"
    if [ -n "$value" ]; then
      printf "%s\n" "$value"
      return 0
    fi
  done
  return 1
}

print_second_ai_copy_prompt() {
  cat >&2 <<\PROMPT

可直接复制给第二个 AI 的提示词：

你是 OpenHouseAI / SmallPhoneAI 的二阶段引导 AI。请在用户设备上独立完成二阶段健康检查签名。

必须先阅读这些文档，按存在的路径优先：
- /root/openhouse/docs/OPENHOUSE_HEALTH_SIGNOFF.md
- /root/openhouse/docs/openhouse-install-flow.md
- /root/openhouse/docs/service-manager.md
- /root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md
- 如果 /root/openhouse/docs 不存在，使用 /root/openhouseai-docs/official 下的同名文档。

任务：
1. 在 bootstrap.sh 所在目录运行 `bash bootstrap.sh status`，读取健康检查 JSON，重点查看 `healthCheck`、`healthSignatures` 或 `bootstrapAiSignatures`、运行栈 readiness、fullHealthCheckDue。
2. 确认一阶段引导 AI 已签名，但二阶段签名缺失或不合格；不要把一阶段签名当作完整通过。
3. 参考文档独立复核 service-manager、pi-agent/pi-web 或 SmallPhone 兼容服务、OpenHouseAI/AionUI(smallhouseai)、cc/codex/Claude Code/Codex 或其它可用 AI 工作台。
4. 签名只需要明文 agent 名，例如 pi、claude-code、codex、openhouseai、hermes 或其它第二个 AI 的稳定名称。
5. 不要在回复、日志或签名记录中写入 API key、token、Authorization、cookie 或其它密钥。
6. 如果复核通过，在 bootstrap.sh 所在目录执行，例如：
   `bash bootstrap.sh sign-second-bootstrap-ai codex`
   或 `bash bootstrap.sh sign-second-bootstrap-ai claude-code`
   或 `bash bootstrap.sh sign-second-bootstrap-ai openhouseai`
7. 再次运行 `bash bootstrap.sh status`，确认完整签名状态为 true；如果仍未通过，向用户报告缺项和下一步。

PROMPT
}

stage1_file="$(find_signature_file 1 || true)"
stage2_file="$(find_signature_file 2 || true)"
stage1_signer=""
stage2_signer=""
[ -n "$stage1_file" ] && stage1_signer="$(read_signer "$stage1_file" || true)"
[ -n "$stage2_file" ] && stage2_signer="$(read_signer "$stage2_file" || true)"

if [ -n "$stage1_signer" ] && [ -n "$stage2_signer" ] && [ "$stage1_signer" != "$stage2_signer" ]; then
  printf "[ok] 引导 AI 双阶段签名：一阶段=%s，二阶段=%s\n" "$stage1_signer" "$stage2_signer"
else
  check_status=1
  if [ -n "$stage1_signer" ] && [ -z "$stage2_signer" ]; then
    printf "[warn] 仅完成一阶段引导 AI 签名，不能视作完整签名通过；一阶段 AI 需要帮助用户启用第二个 AI，并把下面提示词交给第二个 AI 完成二阶段引导签名。\n" >&2
    print_second_ai_copy_prompt
  elif [ -z "$stage1_signer" ] && [ -n "$stage2_signer" ]; then
    printf "[warn] 检测到二阶段引导 AI 签名，但缺少一阶段引导 AI 签名；不能视作完整签名通过。\n" >&2
  elif [ -n "$stage1_signer" ] && [ -n "$stage2_signer" ]; then
    printf "[warn] 一阶段和二阶段引导签名来自同一个 AI；请找第二个 AI 重新完成二阶段引导签名。\n" >&2
    print_second_ai_copy_prompt
  else
    printf "[warn] 尚未完成一阶段和二阶段引导 AI 签名；完整健康检查需要两个不同 AI 分别签名。\n" >&2
  fi
fi

interval_days="${OPENHOUSE_FULL_HEALTH_CHECK_INTERVAL_DAYS:-${SMALLPHONEAI_FULL_HEALTH_CHECK_INTERVAL_DAYS:-7}}"
case "$interval_days" in
  ""|*[!0-9]*) interval_days=7 ;;
esac
[ "$interval_days" -gt 0 ] || interval_days=7
interval_seconds=$((interval_days * 86400))
last_file=""
for candidate in \
  "${OPENHOUSE_FULL_HEALTH_CHECK_LAST_FILE:-}" \
  "${SMALLPHONEAI_FULL_HEALTH_CHECK_LAST_FILE:-}" \
  "${HOME:+$HOME/.openhouseai/health-checks/last-full-check.json}" \
  "${HOME:+$HOME/.openhouseai/health-checks/last-full-check}" \
  "${HOME:+$HOME/.smallphoneai/health-checks/last-full-check.json}" \
  "${HOME:+$HOME/.smallphoneai/health-checks/last-full-check}" \
  "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.openhouseai/health-checks/last-full-check.json}" \
  "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.openhouseai/health-checks/last-full-check}" \
  "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.smallphoneai/health-checks/last-full-check.json}" \
  "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.smallphoneai/health-checks/last-full-check}"; do
  [ -n "$candidate" ] || continue
  if [ -f "$candidate" ]; then
    last_file="$candidate"
    break
  fi
done

last_epoch=0
if [ -n "$last_file" ]; then
  last_epoch="$(stat -c %Y "$last_file" 2>/dev/null || printf "0")"
fi
now_epoch="$(date +%s 2>/dev/null || printf "0")"
if [ "$last_epoch" -gt 0 ] && [ "$now_epoch" -gt 0 ] && [ $((now_epoch - last_epoch)) -lt "$interval_seconds" ]; then
  printf "[ok] 全面健康检查提醒周期：%s 天内已检查\n" "$interval_days"
else
  check_status=1
  if [ "$last_epoch" -gt 0 ]; then
    printf "[warn] 距离上次全面健康检查已超过 %s 天；建议重新执行全面检查并刷新两阶段引导 AI 签名。\n" "$interval_days" >&2
  else
    printf "[warn] 尚未记录上次全面健康检查时间；建议执行全面检查并完成两阶段引导 AI 签名。\n" >&2
  fi
fi

exit "$check_status"
'; then
    :
  else
    missing=1
  fi
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
check_bootstrap_health_signatures

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
