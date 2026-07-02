# pi agent 和插件体系

本文档说明 OpenHouseAI 当前 pi-agent 入口、页面运行时和插件体系。

## 默认核心

- `pi-agent` 是首次配置助手、文档索引员和配置迁移执行者。
- `pi-agent` 是 Android 菜单/侧边栏一级服务入口。
- pi-web 是 `pi-agent` 背后的本地页面运行时。
- service-manager 管理 `pi-agent` 和 `pi-web`。
- Android App 通过 `pi-agent` 入口在 WebView 中加载 pi-web。
- 用户长期使用的主工作台由用户选择，可以是 Claude Code、Codex、Hermes Web 或其它开源项目。

官方文档目录：

```text
/root/openhouse/docs
/root/openhouseai-docs/official
~/openhouseai-docs/official
```

默认地址：

```text
http://127.0.0.1:30141/
```

默认服务 ID：

```text
pi-agent
pi-web
```

默认运行环境变量：

```text
PI_CODING_AGENT_DIR=/root/.pi
```

## 插件目录

pi 默认读取这些扩展目录：

```text
/root/.pi/extensions
/root/.pi/agent/extensions
```

用途：

- `/root/.pi/extensions`：pi-web 新会话当前优先发现扩展的位置。
- `/root/.pi/agent/extensions`：pi CLI 默认全局扩展目录。

默认搜索插件：

```text
multi-platform-search.ts
```

默认工具名：

```text
multi_platform_search
web_search
search_web
search
```

默认搜索源是 Bing，不要求 API key。其他搜索源可作为 best-effort 补充，失败不应影响 Bing。

## 插件刷新

旧 pi-web 会话可能不会自动刷新新工具。安装或更新扩展后，如果工具列表没有变化，先新建 pi-web 会话再验证。

AI agent 检查插件时先看：

```bash
ls -la /root/.pi/extensions
ls -la /root/.pi/agent/extensions
```

不要只因为旧会话看不到工具就判定插件安装失败。

## 服务和侧边栏

长期运行的 pi 服务必须通过 service-manager 管理。

服务清单路径：

```text
$HOME/.config/openhouseai/service-manager/services.d/pi-agent.json
$HOME/.config/openhouseai/service-manager/services.d/pi-web.json
```

侧边栏组件路径：

```text
$HOME/.config/openhouseai/components.d/pi-agent.json
```

组件注册只描述 UI 入口和 service-manager 引用。命令、脚本、工作目录、环境变量和停止方式必须放在 service-manager 的服务清单中。

## 安装网络要求

pi-web 首装使用 APK 内置完整 runtime 包，只做解压、校验、注册和启动；不要通过 `npm install -g` 安装 pi-web tgz，也不要把 pi-web 首装描述为需要 npm registry。Codex、Claude Code、CloudCLI、Node.js、Ubuntu 基础包和其它缺失依赖仍可能需要网络，因此也不要把整个首次安装描述成完全离线安装或网络可选安装。

## 救援助手

Termux 侧救援助手是后置预留能力。本轮不安装、不常驻、不进入首次安装关键路径。

未来如果启用，应由用户选择的工作台、pi-agent 首次配置流程或维护入口安装配置，并保持按需启动。

## 历史能力边界

Operit 不再是默认 agent、默认 UI 或默认插件体系。遇到旧文档或旧入口时，先按历史移除内容处理，不要把它作为当前默认核心。
