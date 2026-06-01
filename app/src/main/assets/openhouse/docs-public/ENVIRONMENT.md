# 运行环境说明

OpenHouseAI 运行在 Android 手机上，结构如下：

- Android 是宿主系统。
- Termux 提供终端环境和包管理。
- Ubuntu 通过 `proot-distro` 安装在 Termux 内。
- OpenCode、Codex CLI、Claude Code、Reasonix 安装在 Ubuntu 内。

## 安装范围

OpenHouseAI 只负责安装和检测：

- Ubuntu proot
- OpenCode
- Codex CLI
- Claude Code
- Reasonix

Node.js 不作为单独可见阶段。Codex、Claude Code 和 Reasonix 安装阶段会在内部检测并按需安装 Node.js。

## 阶段顺序

维护中心的一键阶段顺序是：

1. 准备 Termux 路径、配置和文档。
2. 安装 Termux 基础包。
3. 测速并选择 Ubuntu rootfs 镜像源，然后安装 Ubuntu rootfs。
4. 同步 OpenHouseAI 文档。
5. 安装 Ubuntu 基础包。
6. 设置打开 Termux 后默认进入 Ubuntu。
7. 安装 OpenCode。
8. 安装 Codex CLI。
9. 安装 Claude Code。
10. 安装 Reasonix。

默认进入 Ubuntu 必须在安装 OpenCode、Codex CLI、Claude Code 和 Reasonix 之前完成。

Ubuntu rootfs 安装不会使用代理。安装脚本会先测试内置的 Ubuntu cloud image 镜像源，选择当前可达且较快的 rootfs URL，再执行 `proot-distro install -n ubuntu <rootfs-url>`。如需指定源，可在执行前设置 `OPENHOUSEAI_UBUNTU_ROOTFS_URL`。

## 路径

- Termux 主目录：`/data/data/com.termux/files/home`
- 工作区：`/data/data/com.termux/files/home/workspace`
- 官方文档：`/data/data/com.termux/files/home/openhouseai-docs/official`
- Agent 笔记：`/data/data/com.termux/files/home/openhouseai-docs/agent-notes`
- 启动入口配置：`/data/data/com.termux/files/home/.openhouseai`

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
