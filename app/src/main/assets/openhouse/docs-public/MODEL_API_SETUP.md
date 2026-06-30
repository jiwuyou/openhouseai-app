# Codex、Claude Code 和 CloudCLI 登录/API 配置

本文件说明安装完成后，如何让 Codex CLI、Claude Code 和 CloudCLI 连接大模型服务。

不要把 API key 写入 git 仓库、共享文档、APK 资源、日志或截图。优先使用工具自带登录流程，或只在本机 shell 配置环境变量。

## Codex CLI

Codex CLI 通常有两种使用方式：

1. 使用官方登录流程。
2. 使用 OpenAI API key。

### 官方登录

在 Ubuntu 终端中运行：

```bash
codex login
```

按终端提示完成浏览器登录或设备授权。登录完成后，再运行：

```bash
codex --version
codex
```

### 使用 OpenAI API key

如果你使用 API key，可以在 Ubuntu 的 shell 配置中设置：

```bash
export OPENAI_API_KEY="你的 OpenAI API key"
```

如果需要长期保存，只写入本机的 `~/.bashrc` 或 `~/.profile`，不要写入项目仓库：

```bash
printf '\nexport OPENAI_API_KEY="你的 OpenAI API key"\n' >> ~/.bashrc
```

如果使用 OpenAI 兼容网关，通常还需要设置 base URL。不同网关变量名可能不同，先查看 Codex CLI 当前版本文档或 `codex --help`，确认支持的环境变量后再配置。

## Claude Code

Claude Code 通常有两种使用方式：

1. 使用官方登录流程。
2. 使用 Anthropic API key。

### 官方登录

在 Ubuntu 终端中运行：

```bash
claude login
```

按终端提示完成登录。登录完成后检查：

```bash
claude --version
claude
```

### 使用 Anthropic API key

如果你使用 API key，可以在 Ubuntu 的 shell 配置中设置：

```bash
export ANTHROPIC_API_KEY="你的 Anthropic API key"
```

如果需要长期保存，只写入本机的 `~/.bashrc` 或 `~/.profile`：

```bash
printf '\nexport ANTHROPIC_API_KEY="你的 Anthropic API key"\n' >> ~/.bashrc
```

### 一键配置 Claude Code 使用 DeepSeek

如果要让 Claude Code 使用 DeepSeek 的 Anthropic 兼容接口，可以在 Ubuntu 终端中创建一个本机配置工具：

```bash
mkdir -p "$HOME/bin"
cat > "$HOME/bin/openhouseai-configure-claude-deepseek" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

read -r -p "请输入 DeepSeek API Key: " deepseek_key
if [ -z "$deepseek_key" ]; then
  echo "API Key 不能为空。" >&2
  exit 1
fi

config_file="$HOME/.bashrc"
start_marker="# >>> OpenHouseAI Claude Code DeepSeek >>>"
end_marker="# <<< OpenHouseAI Claude Code DeepSeek <<<"
tmp_file="$(mktemp)"

awk -v start="$start_marker" -v end="$end_marker" '
  $0 == start { skip=1; next }
  $0 == end { skip=0; next }
  skip != 1 { print }
' "$config_file" 2>/dev/null > "$tmp_file" || true

cat >> "$tmp_file" <<CONFIG
$start_marker
export ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic
export ANTHROPIC_AUTH_TOKEN=$deepseek_key
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
echo "Claude Code DeepSeek 配置已写入 $config_file。请执行：source ~/.bashrc"
EOF
chmod +x "$HOME/bin/openhouseai-configure-claude-deepseek"
```

之后运行：

```bash
openhouseai-configure-claude-deepseek
source ~/.bashrc
claude
```

## CloudCLI

CloudCLI / ClaudeCodeUI 是安装完成后的网页工作台入口，运行期由 service-manager 管理。首次安装不会要求填写模型或 API key。

如果 CloudCLI 页面要求登录或连接模型，按页面提示完成。不要把 API Key 粘贴到聊天内容、日志、截图或仓库中。

## 配置检查

重新打开 Termux 后会默认进入 Ubuntu。进入后检查：

```bash
command -v codex
command -v claude
codex --version
claude --version
```

检查环境变量是否存在：

```bash
printenv OPENAI_API_KEY
printenv ANTHROPIC_API_KEY
```

如果没有输出，说明当前 shell 没有加载对应配置。

## 常见问题

### 命令不存在

重新执行维护中心里的 `下载 Codex CLI`、`下载 Claude Code` 或 `下载 CloudCLI` 阶段。

### API key 配置后仍不可用

确认你是在 Ubuntu 内运行，而不是 Termux 外层运行：

```bash
cat /etc/os-release
```

确认 `~/.bashrc` 或 `~/.profile` 已重新加载：

```bash
source ~/.bashrc
```

### 不确定当前工具支持哪些变量

运行：

```bash
codex --help
claude --help
```

以当前安装版本的帮助信息为准。
