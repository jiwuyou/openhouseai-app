# pi Agent and Plugin System

本文档定义 OpenHouseAI 当前主线的 agent 和插件体系。结论是：pi 是默认主 agent，pi-web 是默认主 UI，service-manager 负责它们的运行期生命周期。

## Default Runtime

OpenHouseAI 的默认 agent 运行在 Termux 上的 Ubuntu 内。

```text
Ubuntu
  - pi as the primary agent runtime
  - pi-web as the primary web UI
  - Codex, Claude Code, CloudCLI
  - project workspaces and developer tools

service-manager
  - pi-agent
  - pi-web
  - AionUi local web page (`aionui-web`)
  - CloudCLI and connector services

Android App
  - onboarding
  - status and maintenance
  - WebView entry for pi-web
  - WebView entry for AionUi
  - service controls
```

默认本地入口：

```text
http://127.0.0.1:30141/
```

默认服务 ID：

```text
pi-agent
pi-web
aionui-web
```

默认环境变量：

```text
PI_CODING_AGENT_DIR=/root/.pi
```

## Plugin Directories

pi 的默认扩展目录是：

```text
/root/.pi/extensions
/root/.pi/agent/extensions
```

两个目录都需要文档化和维护：

- `/root/.pi/extensions`：当前 pi-web 新会话优先发现扩展的位置。
- `/root/.pi/agent/extensions`：pi CLI 默认全局扩展目录。

默认搜索扩展：

```text
multi-platform-search.ts
```

该扩展注册的工具包括：

```text
multi_platform_search
web_search
search_web
search
```

默认搜索源是 Bing，不要求 API key。百度、搜狗、夸克等来源可以作为 best-effort fallback，失败不应影响 Bing 结果。

## Session Refresh Rule

pi-web 的旧会话可能不会自动刷新新安装或新修改的工具。安装、删除或更新扩展后，最稳妥的方式是新建 pi-web 会话，再验证工具列表。

AI agent 在排查“插件已安装但工具不可见”时，先检查：

```bash
ls -la /root/.pi/extensions
ls -la /root/.pi/agent/extensions
```

再新建 pi-web 会话验证，不要立即判断为安装失败。

## service-manager Contract

pi、pi-web 和 AionUi 本地页面都必须由 service-manager 管理，不要由 Android UI 直接长期启动。

服务注册应写入：

```text
$HOME/.config/openhouseai/service-manager/services.d/pi-agent.json
$HOME/.config/openhouseai/service-manager/services.d/pi-web.json
$HOME/.config/openhouseai/service-manager/services.d/aionui-web.json
```

组件注册应写入：

```text
$HOME/.config/openhouseai/components.d/pi-web.json
$HOME/.config/openhouseai/components.d/aionui-web.json
```

pi-web 组件入口应使用 WebView：

```json
{
  "id": "pi-web",
  "enabled": true,
  "shellMenu": {
    "title": "pi-agent",
    "subtitle": "pi-agent 本地入口",
    "section": "ai",
    "order": 80,
    "visible": true,
    "favorite": true,
    "entry": {
      "type": "webview",
      "url": "http://127.0.0.1:30141/"
    },
    "controlEntry": {
      "type": "service-control",
      "title": "控制",
      "serviceRefs": [
        "service-manager://services/pi-agent",
        "service-manager://services/pi-web"
      ]
    }
  },
  "smallphoneApp": {},
  "serviceManager": {
    "services": [
      {
        "name": "pi-agent",
        "serviceRef": "service-manager://services/pi-agent"
      },
      {
        "name": "pi-web",
        "serviceRef": "service-manager://services/pi-web"
      }
    ]
  },
  "ai": {}
}
```

组件清单只描述 UI 入口和 service-manager 引用。命令、工作目录、环境变量和停止方式必须放在 service-manager 服务定义中。

AionUi 本地页面的 service id 是 `aionui-web`。Android 桌面/菜单入口应只打开 `http://127.0.0.1:25808/` 的 WebView，并通过 `service-manager://services/aionui-web` 暴露控制入口；它不能由 Android UI 或安装后的菜单逻辑直接长期启动。

脚本型 pi 服务应使用稳定的 process provider 命令形式：

```json
{
  "command": ["sh", "-lc", "openhouse-pi-web-start"]
}
```

不要注册为 `["openhouse-pi-web-start"]` 或 `["/bin/sh", "/root/.local/bin/openhouse-pi-web-start"]`。pi-web 启动脚本最终会进入 `node server.js`；如果 service-manager 跟踪的 PID cmdline 变化，会出现 `stale pidfile` 或 `cmdline mismatch`，Android 运行控制就不能真实控制 pi-agent 页面。

## First Install Scope

首次安装链路应完成：

1. 准备 Termux 和 Ubuntu。
2. 安装 Node.js、Codex CLI、Claude Code、CloudCLI。
3. 安装 service-manager 和必要连接组件。
4. 安装或同步 pi。
5. 解压并校验 APK 内置的 pi-web 完整 runtime。
6. 同步默认扩展，例如 `multi-platform-search.ts`。
7. 注册 `pi-agent` 和 `pi-web`。
8. 注册 pi-web 桌面/主菜单/侧边栏入口。
9. 启动 `pi-agent` 和 `pi-web`。

pi-web 首装使用 APK 内置完整 runtime 包，只做解压、校验、注册和启动；不要通过 `npm install -g` 安装 pi-web tgz，也不要把 pi-web 首装描述为需要 npm registry。Codex、Claude Code、CloudCLI、Node.js、Ubuntu 基础包和其它缺失依赖仍可能需要网络，因此文档和产品文案也不能把整个首次安装描述成完全离线或网络可选安装。

首次安装阶段不要求用户配置默认模型或 API key。安装完成后，用户再按需登录 Codex、Claude Code、CloudCLI 或配置 pi 使用的模型。

## Deferred Host Assistant

Termux 侧救援助手是后置能力和未来预留。本轮不默认安装、不常驻、不进入首次安装关键路径。

未来如果启用，应由已安装好的主 agent 或维护入口安装和配置，并保持按需启动。它可以复用 pi/pi-web 的代码和插件协议，但必须有独立于 Ubuntu 主工作区的部署方案，否则 Ubuntu 损坏时无法兜底。

## Operit Boundary

Operit 已作为 Android 侧完整可选构建恢复，但不属于 pi 插件体系，也不是 Ubuntu payload。`withOperit` flavor 可以包含完整 Operit feature/module、宿主桥接和 Android 入口；`withoutOperit` flavor 不依赖 Operit，也不暴露 Operit 入口。

新的默认能力、首次安装链路、service-manager 服务和 AI 参考文档仍应以 pi、pi-web 和 AionUi 为准。不要把 Operit package/plugin 格式写成默认 OpenHouseAI 插件标准，也不要让 pi、pi-web 或 AionUi 依赖 Operit 才能工作。

两个 flavor 的 Android 包名都保持 `com.termux`。因此 `withOperit` 和 `withoutOperit` APK 不能共存，只能在同签名且 `versionCode` 单调递增时互相升级或替换。
