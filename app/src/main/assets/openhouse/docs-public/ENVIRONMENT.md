# 运行环境说明

OpenHouseAI 运行在 Android 手机上，结构如下：

- Android 是宿主系统。
- Termux 提供终端环境和包管理。
- Ubuntu 通过 `proot-distro` 安装在 Termux 内。
- Node.js 24 LTS、pi-agent、pi-web、service-manager 和基础桥接服务安装在 Ubuntu 内。
- Codex CLI、Claude Code、ClaudeCodeUI / CloudCLI 和 Hermes 由 pi-agent 后置引导安装，也安装在 Ubuntu 内。
- pi 和 pi-web 安装在 Ubuntu 内。
- 安装完成后，service-manager 负责管理 openhouse-connect、`pi-agent`、`pi-web` 和核心后台服务。

## 安装范围

首次安装只负责安装和检测：

- Ubuntu proot
- Node.js 24 LTS
- OpenHouse 文档和后置脚本入口
- service-manager
- openhouse-connect
- pi
- pi-web
- 默认 pi 扩展，例如 `multi-platform-search.ts`
- SmallPhone 兼容服务

Codex CLI、Claude Code、ClaudeCodeUI / CloudCLI 和 Hermes 是后置能力，不阻塞首次安装。后置入口在 `/root/openhouse/scripts`，说明在 `/root/openhouse/docs/AI_TOOL_POSTINSTALL.md`。

## 阶段顺序

维护中心的一键阶段顺序是：

1. 准备 Termux 路径、配置和文档。
2. 安装 Termux 基础包。
3. 测速并选择 Ubuntu rootfs 镜像源，然后安装 Ubuntu rootfs。
4. 同步 OpenHouseAI 文档。
5. 安装 Ubuntu 基础包。
6. 设置打开 Termux 后默认进入 Ubuntu。
7. 安装 Node.js 24 LTS。
8. 同步 OpenHouse 文档和后置脚本入口。
9. 解压校验 APK 内置 pi-agent / pi-web 完整 runtime。
10. 安装并配置 service-manager。
11. 注册并启动 `pi-agent` 和 `pi-web`。
12. 安装 openhouse-connect 和 SmallPhone 兼容服务。
13. 注册 openhouse-connect / SmallPhone 到 service-manager。
14. 同步默认 pi 扩展、service-manager 服务定义和 OpenHouseAI 组件注册。
15. 启动 OpenHouse 基础运行栈。

默认进入 Ubuntu 必须在后置 AI 工具安装之前完成。

pi-web 首装使用 APK 内置完整 runtime，只做解压、校验、注册和启动，不通过 `npm install -g` 安装 pi-web tgz。Node.js、Ubuntu 基础包和其它缺失依赖阶段仍可能需要网络。Codex CLI、Claude Code、ClaudeCodeUI / CloudCLI 和 Hermes 的网络安装放到 pi-agent 后置引导阶段。

Ubuntu rootfs 安装不会使用代理。安装脚本会先测试内置的 Ubuntu cloud image 镜像源，选择当前可达且较快的 rootfs URL，再执行 `proot-distro install -n ubuntu <rootfs-url>`。如需指定源，可在执行前设置 `OPENHOUSEAI_UBUNTU_ROOTFS_URL`。

## 路径

OpenHouse 的路径必须按层理解。`/root` 是 Ubuntu 内的 root home，不是 Android 系统根目录，也不是 Termux 外层 home。

### Ubuntu 内看到的路径

这些路径在 `proot-distro login ubuntu` 后可见，是 OpenHouse 的主要工作区：

| 路径 | 用途 |
| --- | --- |
| `/root` | Ubuntu root 用户主目录，pi-web 新项目默认建议目录。 |
| `/root/openhouse/docs` | 推荐官方文档目录，给用户和 AI 使用。 |
| `/root/openhouse/scripts` | 后置 AI 工具安装和检查入口。 |
| `/root/openhouseai-docs/official` | 兼容旧路径的官方文档目录。 |
| `/root/openhouseai-docs/agent-notes` | AI 或维护任务可写的笔记目录。 |
| `/root/projects` | 常见项目目录。 |
| `/root/.pi` | pi 默认运行目录。 |
| `/root/.pi/extensions` | pi-web 新会话优先发现扩展的位置。 |
| `/root/.pi/agent/extensions` | pi CLI 默认全局扩展目录。 |
| `/root/workspace` | 可选工作区，具体是否存在以当前环境为准。 |

在 Ubuntu 中，`~` 通常就是 `/root`。面向用户和 AI 的推荐文档路径是 `/root/openhouse/docs`，兼容旧路径是 `/root/openhouseai-docs/official`。pi-web 默认提示词和 pi-agent 文档引用应优先使用 `/root/openhouse/docs/<file>`，旧环境可回退到 `/root/openhouseai-docs/official/<file>`。

### Termux 文件系统真实路径

这些路径属于 Android App 沙箱内的 Termux 外层：

| 路径 | 用途 |
| --- | --- |
| `/data/data/com.termux/files/home` | Termux 外层 home。bootstrap、安装日志、Termux 侧配置通常在这里。 |
| `/data/data/com.termux/files/usr` | Termux prefix。Termux 包、二进制、库和 proot-distro 安装在这里。 |
| `/data/data/com.termux/files/home/.smallphoneai-bootstrap` | bootstrap 脚本和状态目录，具体是否存在以当前安装为准。 |
| `/data/data/com.termux/files/home/.maintainer-logs` | 常见维护/安装日志目录，具体日志名以当前版本为准。 |
| `/data/data/com.termux/files/home/.openhouseai` | 启动入口和 OpenHouse 侧配置可能使用的目录。 |

Termux 外层用于修底座、修 Ubuntu、查安装日志、调用 Android 贴身能力。不要把用户项目构建、Codex、Claude Code 或长期服务默认放在 Termux 外层执行。

### Ubuntu rootfs 在 Termux 中的位置

Ubuntu 通过 `proot-distro` 安装在 Termux prefix 下，常见真实路径是：

```text
/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu
```

这个路径说明 Ubuntu 数据在 Termux 文件系统中的物理位置。普通操作和 AI 任务应优先通过以下方式进入 Ubuntu：

```bash
proot-distro login ubuntu
```

不要在未确认和未备份的情况下直接修改 rootfs 目录内部文件。直接改 rootfs 容易绕过环境变量、权限和启动脚本，增加损坏风险。

### Android App 里的终端入口

OpenHouse 菜单/终端页面中可进入 Termux 或 Ubuntu 终端，具体入口名称以当前 App 为准：

- Termux 终端：Android 侧 Termux shell，home 通常是 `/data/data/com.termux/files/home`。用于底座、bootstrap、proot-distro、安装日志和救援。
- Ubuntu 终端：通过 proot-distro 进入的 Ubuntu shell，home 通常是 `/root`。用于开发、AI CLI、用户项目、pi、CloudCLI、Claude Code 和长期服务检查。

安装完成后，Termux 终端可能被配置为打开后自动进入 Ubuntu。排障时不能只看入口名称，要用命令确认当前层。

### 跨层调用方法

#### Termux -> Ubuntu

如果当前在 Termux 外层，需要执行 Ubuntu 内命令，使用 `proot-distro login ubuntu -- <command>`：

```bash
proot-distro login ubuntu -- bash -lc 'pwd; node -v'
proot-distro login ubuntu -- bash -lc 'cd /root/projects && ls -la'
```

如果已经在 Ubuntu 内，不要再嵌套执行 `proot-distro login ubuntu -- ...`。先用 `pwd`、`echo "$HOME"`、`cat /etc/os-release` 判断当前层。

#### Ubuntu -> Termux

Ubuntu 是 proot 内层，不能直接假设可以安全调用外层 Termux shell。需要 Termux 外层能力时，优先选择：

1. 从 OpenHouse 的 Termux 终端入口执行底座命令。
2. 通过 Android App 维护入口执行安装、启动、修复或日志收集。
3. 使用项目已经暴露的 bridge、脚本或 service-manager 动作。
4. 先查 `/root/openhouse/docs`、bootstrap 文档和 `SERVICE_MANAGER.md`，确认是否已有受支持的跨层命令。

常见 Termux 真实路径是：

```text
/data/data/com.termux/files/usr/bin
/data/data/com.termux/files/home
```

但这不代表 Ubuntu 里直接执行这些 binary 或直接修改 Termux prefix 一定安全。不要在 Ubuntu 内盲目运行 `/data/data/com.termux/files/usr/bin/pkg`，也不要直接修改 `/data/data/com.termux/files/usr`。需要修 proot、apt、Android 权限、Termux 包或底座时，应回到 Termux 外层或使用受支持的维护入口。

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

快速判断当前层：

```bash
pwd
echo "$HOME"
cat /etc/os-release 2>/dev/null || true
command -v proot-distro 2>/dev/null || true
test -d /data/data/com.termux/files/usr && echo termux-files-visible || true
test -d /root && echo root-path-visible || true
openhouseai-env-probe 2>/dev/null || smallphoneai-env-probe 2>/dev/null || true
```

判断规则：

- `$HOME=/root` 且 `/etc/os-release` 显示 Ubuntu：当前在 Ubuntu 内。
- `$HOME=/data/data/com.termux/files/home`：当前在 Termux 外层。
- `command -v proot-distro` 有输出：通常说明可以从 Termux 外层管理 Ubuntu；Ubuntu 内不应依赖它做日常任务。
- 看到 `/root/openhouse/docs`：通常是在 Ubuntu 内看到的文档路径。
- 看到 `/data/data/com.termux/files/usr`：这是 Termux 文件系统真实路径，不等于 Ubuntu 的 `/usr`。

## 后置能力

Termux 侧救援助手是未来预留能力。本轮不默认安装、不常驻、不进入首次安装关键路径。后续可以由用户选择的工作台、pi-agent 首次配置流程或维护入口安装配置救援助手。
