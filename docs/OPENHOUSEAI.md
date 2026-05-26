# OpenHouseAI 概览

OpenHouseAI 在 Termux 之上提供一个收窄的安装和维护中心，用来把 Android 手机准备成 Ubuntu + Agent CLI 的本地工作环境。

## 主要功能

- 从 Termux 主界面进入维护中心。
- 权限区覆盖电池优化、悬浮窗和存储访问。
- 支持分步执行和阶段一键执行。
- 阶段顺序明确展示 Ubuntu、文档同步、默认进入 Ubuntu、OpenCode、Codex CLI 和 Claude Code。
- 支持 APK 内置、本地用户文件和 GitHub raw 在线维护清单。
- 保留本地网页维护器和 OpenCode 端口设置能力。

## 安装范围

OpenHouseAI 安装：
- Ubuntu proot
- OpenCode
- Codex CLI
- Claude Code

Node.js 不作为单独可见阶段。Codex 和 Claude Code 阶段会在内部检测并按需安装。

## 阶段顺序

```text
prepare -> termux_packages -> install_ubuntu -> sync_official_docs -> ubuntu_packages -> entry_ubuntu -> install_opencode -> install_codex -> install_claude_code
```

`entry_ubuntu` 必须在安装三个 Agent CLI 前执行，用于设置打开 Termux 后默认进入 Ubuntu。

## 在线维护源

默认在线源：

```text
https://raw.githubusercontent.com/jiwuyou/openhouseai-bootstrap/main/openhouseai-manifest.json
```

在线维护源不包含 API key。用户应在本地通过对应工具的官方登录或环境变量配置凭据。

## 相关仓库

- Bootstrap 脚本：<https://github.com/jiwuyou/openhouseai-bootstrap>
