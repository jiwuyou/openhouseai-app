# Codex、Claude Code 和 CloudCLI 登录/API 配置

本文件说明后置安装完成后，如何让 Codex CLI、Claude Code 和 CloudCLI 连接大模型服务。

如果命令尚未安装，先由 pi-agent 使用：

```bash
/root/openhouse/scripts/install-codex.sh
/root/openhouse/scripts/install-claude-code.sh
/root/openhouse/scripts/install-cloudcli.sh
/root/openhouse/scripts/check-ai-tools.sh
```

不要把 API key 写入 git 仓库、共享文档、APK 资源、日志或截图。优先使用工具自带登录流程，或只在本机 shell 配置环境变量。

如果用户正在 `pi-agent` 中配置 CloudCLI 里的 Claude Code，优先阅读 `CLOUDCLI_CLAUDE_CODE.md`。该文档描述网页配置、`agent.js` 修复、默认账号密码和测通目标。

## OpenHouseAI / AionUi 模型配置

OpenHouseAI 中的 AionUi 模型配置应以 AionUi 官方文档和 AionUi 后端字段为准。AionUi README 指向的官方入口是 `https://github.com/iOfficeAI/AionUi/wiki/LLM-Configuration`；本地实现里的 provider 结构是 `IProvider`，核心字段为 `platform`、`name`、`base_url`、`api_key`、`models[]`、`model_protocols`。

用户侧推荐流程：

1. 打开 OpenHouseAI / AionUi 的设置页，进入模型或 AI 核心配置。
2. 点击“添加模型”。
3. 先选择“模型平台”。能用内置平台时优先用内置平台，例如 Gemini、Gemini Vertex AI、Anthropic、OpenAI、AWS Bedrock、New API、DeepSeek、OpenRouter、SiliconFlow、Ollama、LM Studio 等。
4. 只有平台列表没有覆盖当前服务时，才选择“自定义（兼容 OpenAI）”。
5. 填写 `base url` 和 `API Key`。多个 Key 可以每行一个，AionUi 会做轮询。
6. 先使用 AionUi 自带探测工具，让它识别 API 协议、修正可修正的 `base url`、拉取模型列表并测试 Key。
7. 选择或输入“模型名称 / 模型 ID”。这里必须填供应商真实模型 ID。
8. 如果选择的是 New API 网关，要为每个模型确认“请求协议”，例如 OpenAI、Gemini 或 Anthropic。
9. 保存后执行模型健康检查，确认该模型状态为 healthy。
10. 把健康检查通过的模型设为默认模型。

AionUi 界面字段和后端字段的对应关系：

| AionUi 字段 | 后端字段 | 应当填写什么 |
| --- | --- | --- |
| 模型平台 | `platform` | 选择内置平台；New API 用 `new-api`；通用 OpenAI 兼容服务用“自定义（兼容 OpenAI）”。 |
| 平台名称 / 模型供应商 | `name` | 给这组配置起一个可识别名字，例如 `DeepSeek`、`OpenRouter`、`本地 Ollama`。 |
| base url | `base_url` | API 根地址。普通模式下 AionUi 会自动拼接请求路径。 |
| 完整URL | `is_full_url` | 只有供应商要求直接请求完整接口地址时才开启。 |
| API Key | `api_key` | 认证凭据。多个 Key 每行一个；不要写入仓库、文档、日志或截图。 |
| 模型名称 / 模型 ID | `models[]` | 供应商真实模型 ID，可以选择远端列表，也可以手动输入。 |
| New API 请求协议 | `model_protocols[model]` | 仅 New API 平台使用；同一个网关下不同模型可分别指定 OpenAI、Gemini 或 Anthropic。 |

优先使用 AionUi 自带探测工具：

| 探测能力 | AionUi 入口 / 后端接口 | 用途 |
| --- | --- | --- |
| 协议检测 | 添加模型页自动检测，后端为 `POST /api/providers/detect-protocol` | 根据 `base_url` 和 `api_key` 判断 OpenAI、Gemini、Anthropic 或 unknown，并给出切换平台建议。 |
| 模型列表拉取 | 添加模型页模型下拉，后端为 `POST /api/providers/fetch-models` | 在保存 provider 前拉取远端模型列表；如果返回 `fixed_base_url`，优先采用修正后的地址。 |
| Key 测试 | “测试密钥 / 测试所有密钥” | 检查单个或多个 API Key 是否有效；多个 Key 每行一个。 |
| 模型健康检查 | 模型列表里的健康状态检查 | 保存后对某个 provider + model 做真实请求，记录 `model_health.status`、延迟和错误摘要。 |

pi-agent 或维护人员通过 AionUi 后端 API 配置时，也应沿用这些能力，而不是直接手写配置后宣布完成。只有 AionUi 自身探测成功，才说明 AionUi 内置 agent 可以使用该模型。

填写 `base url` 时要注意：

- 普通模式下，AionUi 提示“系统会自动拼接请求路径”。因此应填写服务根路径，不要把 `/chat/completions`、`/messages`、`/generateContent` 等最终接口路径误填进去。
- New API 网关可以填写网关根地址，再通过每个模型的“请求协议”路由到上游。
- 如果用户手里的地址本身就是完整请求 URL，才使用“完整URL”模式。
- 本地模型如 Ollama / LM Studio 通常走自定义平台或对应预设，按它暴露的 OpenAI 兼容地址填写。

首次 OpenHouse 配置只能把 AionUi 已测通的模型当作来源配置。迁移到 Claude Code、Codex 或 CloudCLI 时，仍必须重新确认目标工具需要的协议、环境变量和配置文件格式。

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

CloudCLI / ClaudeCodeUI 是后置网页入口，运行期由 service-manager 管理。首次安装不会要求填写模型或 API key，也不会因为 CloudCLI 缺失而阻塞 pi-web / pi-agent。

如果 CloudCLI 页面要求登录或连接模型，按页面提示完成。不要把 API Key 粘贴到聊天内容、日志、截图或仓库中。

OpenHouseAI 的菜单中，CloudCLI / Claude Code / Codex 合并在 `cc/codex` 统一入口里。默认本地账号密码是：

```text
admin
123456
```

这组默认账号密码仅限本机首次使用。后续如果服务暴露到非本机网络，或用户希望长期使用，应修改密码并限制监听地址。

通过 pi-agent 配置 CloudCLI 中的 Claude Code 时，用户需要一次性提供：

- URL
- key/token
- model id

配置完成后，测通目标是 CloudCLI 页面里的 Claude Code 能正常使用，而不是只在 Ubuntu 终端里运行 `claude` 命令。

## 配置检查

重新打开 Termux 后会默认进入 Ubuntu。进入后检查：

```bash
command -v codex
command -v claude
command -v cloudcli
codex --version
claude --version
cloudcli version || cloudcli --version
```

检查环境变量是否存在：

```bash
printenv OPENAI_API_KEY
printenv ANTHROPIC_API_KEY
```

如果没有输出，说明当前 shell 没有加载对应配置。

## 常见问题

### 命令不存在

执行 `/root/openhouse/scripts/check-ai-tools.sh` 确认缺失项，再执行对应后置安装脚本。

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
