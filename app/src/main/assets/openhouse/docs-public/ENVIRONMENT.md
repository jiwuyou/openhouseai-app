# 运行环境说明

OpenHouseAI 运行在 Android 手机上，结构如下：

- Android 是宿主系统。
- Termux 提供终端环境和包管理。
- Ubuntu 通过 `proot-distro` 安装在 Termux 内。
- Node.js 24 LTS、Codex CLI、Claude Code、ClaudeCodeUI / CloudCLI 安装在 Ubuntu 内。
- pi 和 pi-web 安装在 Ubuntu 内。
- 安装完成后，service-manager 负责管理 openhouse-connect、`pi-agent`、`pi-web` 和核心后台服务。

## 安装范围

OpenHouseAI 只负责安装和检测：

- Ubuntu proot
- Node.js 24 LTS
- Codex CLI
- Claude Code
- ClaudeCodeUI / CloudCLI
- service-manager
- openhouse-connect
- pi
- pi-web
- 默认 pi 扩展，例如 `multi-platform-search.ts`

Node.js 24 LTS 是单独可见阶段，排在 Codex CLI、Claude Code 和 ClaudeCodeUI / CloudCLI 之前。后续阶段只检查并使用该 Node.js runtime，不再各自隐式安装系统 Node.js。

## 阶段顺序

维护中心的一键阶段顺序是：

1. 准备 Termux 路径、配置和文档。
2. 安装 Termux 基础包。
3. 测速并选择 Ubuntu rootfs 镜像源，然后安装 Ubuntu rootfs。
4. 同步 OpenHouseAI 文档。
5. 安装 Ubuntu 基础包。
6. 设置打开 Termux 后默认进入 Ubuntu。
7. 安装 Node.js 24 LTS。
8. 安装 Codex CLI。
9. 安装 Claude Code。
10. 安装 ClaudeCodeUI / CloudCLI。
11. 安装 service-manager、openhouse-connect 和 pi，并解压校验 APK 内置 pi-web 完整 runtime。
12. 同步默认 pi 扩展、service-manager 服务定义和 OpenHouseAI 组件注册。
13. 启动 `pi-agent` 和 `pi-web`。

默认进入 Ubuntu 必须在安装 Node.js 24 LTS、Codex CLI、Claude Code 和 ClaudeCodeUI / CloudCLI 之前完成。

pi-web 首装使用 APK 内置完整 runtime，只做解压、校验、注册和启动，不通过 `npm install -g` 安装 pi-web tgz。Node.js、Codex CLI、Claude Code、ClaudeCodeUI / CloudCLI、Ubuntu 基础包和其它缺失依赖阶段仍可能需要网络。

Ubuntu rootfs 安装不会使用代理。安装脚本会先测试内置的 Ubuntu cloud image 镜像源，选择当前可达且较快的 rootfs URL，再执行 `proot-distro install -n ubuntu <rootfs-url>`。如需指定源，可在执行前设置 `OPENHOUSEAI_UBUNTU_ROOTFS_URL`。

## 路径

- Termux 主目录：`/data/data/com.termux/files/home`
- 工作区：`/data/data/com.termux/files/home/workspace`
- 官方文档：`/data/data/com.termux/files/home/openhouseai-docs/official`
- Agent 笔记：`/data/data/com.termux/files/home/openhouseai-docs/agent-notes`
- 启动入口配置：`/data/data/com.termux/files/home/.openhouseai`
- pi 默认目录：`/root/.pi`
- pi 扩展目录：`/root/.pi/extensions`
- pi CLI 扩展目录：`/root/.pi/agent/extensions`

Ubuntu 中如果存在以下短路径，优先使用短路径：

- `~/openhouseai-docs/official`
- `~/openhouseai-docs/agent-notes`
- `~/openhouseai-links/docs-path.txt`
- `~/openhouseai-links/workspace-path.txt`

## 环境检测

每个安装阶段都会先检测当前终端环境。

预期探测命令：

- Termux：`openhouseai-env-probe`
- Ubuntu：`~/bin/openhouseai-env-probe`

如果 Agent 不确定当前运行在哪里，应先读取本文件，再执行用户任务。

## 后置能力

Termux 侧救援助手是未来预留能力。本轮不默认安装、不常驻、不进入首次安装关键路径。主 agent 安装完成后，后续可以再由主 agent 或维护入口安装配置救援助手。
