# pi agent 和插件体系

本文档说明 OpenHouseAI 当前默认 agent、UI 和插件体系。

## 默认核心

- pi 是默认主 agent。
- pi-web 是默认主 UI。
- service-manager 管理 `pi-agent` 和 `pi-web`。
- Android App 通过 WebView 打开 pi-web。

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
$HOME/.config/openhouseai/components.d/pi-web.json
```

组件注册只描述 UI 入口和 service-manager 引用。命令、脚本、工作目录、环境变量和停止方式必须放在 service-manager 的服务清单中。

## 安装网络要求

pi 和 pi-web 的项目包可以随 APK 分发，用于固定默认版本和减少现场同步成本；但 npm 依赖解析、缺失依赖安装和 registry 访问仍可能需要网络。不要把首次安装描述成完全离线安装或网络可选安装。

## 救援助手

Termux 侧救援助手是后置预留能力。本轮不安装、不常驻、不进入首次安装关键路径。

未来如果启用，应由主 agent 或维护入口安装配置，并保持按需启动。

## 历史能力边界

Operit 不再是默认 agent、默认 UI 或默认插件体系。遇到旧文档或旧入口时，先按历史移除内容处理，不要把它作为当前默认核心。
