set +x
umask 077

KEY_FILE="${OPENHOUSEAI_DEEPSEEK_KEY_FILE:-__DEEPSEEK_KEY_FILE__}"

require_ubuntu

if [ ! -s "$KEY_FILE" ]; then
  log "未找到 DeepSeek API Key 临时文件。请重新填写 Key。"
  exit 2
fi

DEEPSEEK_API_KEY="$(tr -d '\r\n' < "$KEY_FILE")"
rm -f "$KEY_FILE"
CONFIGURE_OPENCODE="${OPENHOUSEAI_CONFIGURE_OPENCODE:-1}"
CONFIGURE_CLAUDE="${OPENHOUSEAI_CONFIGURE_CLAUDE:-1}"
CONFIGURE_REASONIX="${OPENHOUSEAI_CONFIGURE_REASONIX:-1}"

if [ -z "$DEEPSEEK_API_KEY" ]; then
  log "DeepSeek API Key 为空。"
  exit 2
fi

if [ "$CONFIGURE_OPENCODE" != "1" ] && [ "$CONFIGURE_CLAUDE" != "1" ] && [ "$CONFIGURE_REASONIX" != "1" ]; then
  log "未选择需要配置的 AI 软件。"
  exit 2
fi

log "正在按选择配置 DeepSeek API Key。"

run_ubuntu_logged env \
  DEEPSEEK_API_KEY="$DEEPSEEK_API_KEY" \
  OPENHOUSEAI_CONFIGURE_OPENCODE="$CONFIGURE_OPENCODE" \
  OPENHOUSEAI_CONFIGURE_CLAUDE="$CONFIGURE_CLAUDE" \
  OPENHOUSEAI_CONFIGURE_REASONIX="$CONFIGURE_REASONIX" \
  bash -lc 'set -euo pipefail

if [ "${OPENHOUSEAI_CONFIGURE_OPENCODE:-1}" = "1" ]; then
mkdir -p "$HOME/.config/openhouseai" "$HOME/.config/opencode"
printf "%s" "$DEEPSEEK_API_KEY" > "$HOME/.config/openhouseai/deepseek-api-key"
chmod 600 "$HOME/.config/openhouseai/deepseek-api-key"

cat > "$HOME/.config/opencode/opencode.json" <<'"'"'JSON'"'"'
{
  "$schema": "https://opencode.ai/config.json",
  "model": "deepseek/deepseek-v4-pro",
  "small_model": "deepseek/deepseek-v4-flash",
  "provider": {
    "deepseek": {
      "models": {
        "deepseek-v4-pro": {
          "name": "DeepSeek V4 Pro"
        },
        "deepseek-v4-flash": {
          "name": "DeepSeek V4 Flash"
        }
      },
      "options": {
        "apiKey": "{file:~/.config/openhouseai/deepseek-api-key}"
      }
    }
  }
}
JSON
chmod 600 "$HOME/.config/opencode/opencode.json"
echo "OpenCode 配置：$HOME/.config/opencode/opencode.json"
fi

if [ "${OPENHOUSEAI_CONFIGURE_REASONIX:-1}" = "1" ]; then
mkdir -p "$HOME/.reasonix"
cat > "$HOME/.reasonix/config.json" <<JSON
{
  "lang": "zh-CN",
  "apiKey": "$DEEPSEEK_API_KEY",
  "theme": "dark",
  "mcp": [],
  "setupCompleted": true,
  "editMode": "review",
  "projects": {
    "/root": {
      "shellAllowed": [
        "reasonix",
        "which",
        "curl"
      ]
    }
  },
  "search": true,
  "model": "deepseek-v4-pro"
}
JSON
chmod 600 "$HOME/.reasonix/config.json"
echo "Reasonix 配置：$HOME/.reasonix/config.json"
fi

if [ "${OPENHOUSEAI_CONFIGURE_CLAUDE:-1}" = "1" ]; then
config_file="$HOME/.bashrc"
start_marker="# >>> OpenHouseAI Claude Code DeepSeek >>>"
end_marker="# <<< OpenHouseAI Claude Code DeepSeek <<<"
tmp_file="$(mktemp)"

awk -v start="$start_marker" -v end="$end_marker" '"'"'
  $0 == start { skip=1; next }
  $0 == end { skip=0; next }
  skip != 1 { print }
'"'"' "$config_file" 2>/dev/null > "$tmp_file" || true

cat >> "$tmp_file" <<CONFIG
$start_marker
export ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic
export ANTHROPIC_AUTH_TOKEN="$DEEPSEEK_API_KEY"
export ANTHROPIC_MODEL=deepseek-v4-pro
export ANTHROPIC_DEFAULT_OPUS_MODEL=deepseek-v4-pro
export ANTHROPIC_DEFAULT_SONNET_MODEL=deepseek-v4-pro
export ANTHROPIC_DEFAULT_HAIKU_MODEL=deepseek-v4-flash
export CLAUDE_CODE_SUBAGENT_MODEL=deepseek-v4-flash
export CLAUDE_CODE_EFFORT_LEVEL=max
$end_marker
CONFIG

mv "$tmp_file" "$config_file"
chmod 600 "$config_file"

echo "Claude Code 环境变量已写入：$config_file"
fi
'

unset DEEPSEEK_API_KEY
log "DeepSeek API Key 配置完成。重新进入 Ubuntu 后对应配置会生效。"
