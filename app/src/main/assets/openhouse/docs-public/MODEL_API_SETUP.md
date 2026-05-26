# Codex 和 Claude Code 登录/API 配置

本文件说明安装完成后，如何让 Codex CLI 和 Claude Code 连接大模型服务。

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

重新执行维护中心里的 `下载 Codex CLI` 或 `下载 Claude Code` 阶段。

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
