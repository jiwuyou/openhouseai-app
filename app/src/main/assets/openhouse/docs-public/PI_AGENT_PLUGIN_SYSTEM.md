# pi agent 和插件体系

本文档说明 OpenHouseAI 当前 pi-agent 入口、页面运行时和插件体系。

## 默认核心

- `pi-agent` 是首次配置助手、文档索引员和配置迁移执行者。
- `pi-agent` 是 Android 菜单/侧边栏一级服务入口。
- pi-web 是 `pi-agent` 背后的本地页面运行时。
- service-manager 管理 `pi-agent` 和 `pi-web`。
- Android App 通过 `pi-agent` 入口在 WebView 中加载 pi-web。
- 用户长期使用的主工作台由用户选择，可以是 Claude Code、Codex、Hermes Web 或其它开源项目。
- Codex、Claude Code、CloudCLI 和 Hermes 由 pi-agent 后置引导安装，入口脚本在 `/root/openhouse/scripts`。

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
PI_CODING_AGENT_DIR=/data/data/com.termux/files/home/.pi
```

## 插件目录

pi 默认读取这些扩展目录：

```text
/data/data/com.termux/files/home/.pi/extensions
/data/data/com.termux/files/home/.pi/agent/extensions
```

用途：

- `/data/data/com.termux/files/home/.pi/extensions`：pi-web 新会话当前优先发现扩展的位置。
- `/data/data/com.termux/files/home/.pi/agent/extensions`：pi CLI 默认全局扩展目录。

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
ls -la /data/data/com.termux/files/home/.pi/extensions
ls -la /data/data/com.termux/files/home/.pi/agent/extensions
```

不要只因为旧会话看不到工具就判定插件安装失败。

## 服务和桌面/侧边栏

长期运行的 pi 服务必须通过 service-manager 管理。

服务清单路径：

```text
$HOME/.config/openhouseai/service-manager/services.d/pi-agent.json
$HOME/.config/openhouseai/service-manager/services.d/pi-web.json
```

桌面/侧边栏组件路径：

```text
$HOME/.config/openhouseai/components.d/pi-agent.json
```

组件注册只描述 UI 入口、桌面展示和 service-manager 引用。命令、脚本、工作目录、环境变量和停止方式必须放在 service-manager 的服务清单中。

脚本型 pi 服务应注册为 Termux native provider，并用稳定的 shell supervisor 跟踪子进程：

```json
{
  "provider": "termux-process",
  "command": [
    "sh",
    "-lc",
    "openhouse-pi-web-start & child=$!; trap 'kill -TERM $child 2>/dev/null; wait $child 2>/dev/null || true' TERM INT HUP; wait $child"
  ],
  "runtime": {
    "strategy": "termux-process",
    "runtime": "termux"
  }
}
```

不要注册为 `["openhouse-pi-web-start"]` 或 `["/bin/sh", "/data/data/com.termux/files/home/.local/bin/openhouse-pi-web-start"]`。pi-web 启动脚本最终会进入 `node server.js`；service-manager 应跟踪稳定的 shell supervisor，而不是会被 Node/Next 改写标题的业务进程。

## 安装网络要求

pi-web 首装使用 APK 内置完整 runtime 包，只做解压、校验、注册和启动；不要通过 `npm install -g` 安装 pi-web tgz，也不要把 pi-web 首装描述为需要 npm registry。Node.js、Ubuntu 基础包和其它缺失依赖仍可能需要网络。Codex、Claude Code、CloudCLI 和 Hermes 的网络安装放到 pi-agent 后置引导阶段，因此它们失败不应阻塞首次进入 pi-agent。

## 救援助手

Termux 侧救援助手是后置预留能力。本轮不安装、不常驻、不进入首次安装关键路径。

未来如果启用，应由用户选择的工作台、pi-agent 首次配置流程或维护入口安装配置，并保持按需启动。

## Operit 边界

Operit 已作为 Android 侧完整可选构建恢复，但不属于 pi 插件体系，也不是 Ubuntu payload。`withOperit` flavor 可以包含完整 Operit feature/module、宿主桥接和 Android 入口；`withoutOperit` flavor 不依赖 Operit，也不暴露 Operit 入口。

默认能力、首次安装链路、service-manager 服务和 AI 参考文档仍应以 pi、pi-web 和 AionUi 为准。不要把 Operit package/plugin 格式写成默认 OpenHouseAI 插件标准，也不要让 pi、pi-web 或 AionUi 依赖 Operit 才能工作。

两个 flavor 的 Android 包名都保持 `com.termux`。因此 `withOperit` 和 `withoutOperit` APK 不能共存，只能在同签名且 `versionCode` 单调递增时互相升级或替换。
