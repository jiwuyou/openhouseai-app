# 内置浏览器和 WebView

OpenHouse 可以通过内置浏览器或 Android WebView 打开本地网页服务，例如 pi-web、CloudCLI、Claude Code、Hermes Web 或其它用户安装的工作台。

WebView 与手机外部浏览器并不完全相同。调试页面问题时，AI 应同时考虑服务端状态、前端兼容性、WebView 限制和端口绑定。

OpenHouse 桌面页本身是原生桌面壳，不是全 WebView 多窗口。桌面 App 可以打开 WebView，但桌面不预创建多个 WebView，也不要求多个 WebView 常驻；WebView 主帧加载失败时，应回到桌面 App 的状态面板，提供重试、重启服务、日志和服务控制入口。桌面页隐藏顶部控制栏，进入 WebView App 后才显示最小页壳动作；用户可以把控制栏收起为可拖动、吸附并持久化位置的白黑渐变悬浮球。

## WebView App 页壳

App WebView 页壳层不要重复显示 App 名。App 名只用于桌面图标、App 列表、服务/状态面板、无障碍标签和日志排障；进入 App 后，页面内容自己决定标题和导航，外层壳不再额外占一行显示 App 名。

页壳保留必要动作：

- 侧栏：打开全局侧边栏或菜单。
- 桌面：回到 OpenHouse 桌面。
- 刷新：重新加载当前 WebView。
- 收起：把页壳收起为悬浮球。
- 控制：打开当前 App 关联服务的状态、日志、启动、停止、重启和修复入口。
- 用浏览器打开：把当前 URL 交给内置浏览器或系统可用浏览器，用于排查 WebView 兼容问题。

如果 App 自己的页面顶部已经有标题、标签页或导航，外层页壳不要再制造第二套标题栏。

## WebView 窗口保留

OpenHouse 可以保留少量最近使用的 WebView，减少用户在几个 App 间来回切换时的重载感。默认保留最近 `2` 个 WebView；可配置范围是 `0-5`。

保留规则：

- 同一个 App 和同一个 URL 优先复用已有 WebView。
- 超出数量后按 LRU 清理最久未使用的 WebView。
- `0` 表示不保留，离开 App 即允许销毁 WebView。
- 保留 WebView 不是后台无限运行，也不是长期任务机制。
- App 必须能从持久化状态恢复，例如服务端数据、localStorage、IndexedDB、URL 参数或自己的状态接口。

长期服务、模型进程、文件同步、MCP server 或定时任务仍必须由 service-manager 管理。不要把 WebView 是否被保留当成 App 正常工作的前提。

## 常见本地服务

| 服务 | 常见用途 | 说明 |
| --- | --- | --- |
| pi-web | pi-agent 背后的本地页面运行时 | 默认本地端口以 service-manager 注册为准 |
| CloudCLI / Claude Code | `cc/codex` 统一入口中的网页工作台 | 需要模型配置和服务连通 |
| Hermes Web | 可选高级工作台 | 安装后应注册到 service-manager |
| 用户自定义服务 | 开源工作台、知识库、项目管理系统 | 需要 service-manager 和侧边栏注册 |

## 受控浏览器命令

OpenHouse 还提供一个原生“受控浏览器”入口。它和普通 WebView App 不同：普通 WebView App 主要由顶部栏控制刷新、返回桌面和服务控制；受控浏览器可以被 Termux、Ubuntu 或 AI agent 通过命令控制。

安装准备阶段会注入命令：

```bash
openhouse-browser --help
```

常见路径：

```text
/data/data/com.termux/files/usr/bin/openhouse-browser
/usr/local/bin/openhouse-browser
```

第二个是 Ubuntu 内的 wrapper，最终仍调用 Termux 外层命令。使用前需要确保 OpenHouse App 可接收外部命令，准备阶段会写入 `allow-external-apps = true`。

### 页面和标签页

| 命令 | 用途 |
| --- | --- |
| `open URL` | 在当前标签页打开 URL 或搜索词。 |
| `new-tab URL` | 新建标签页并打开 URL。 |
| `switch INDEX_OR_ID` | 切换到指定标签页，参数可以是序号或 tab id。 |
| `close` | 关闭当前标签页。 |
| `reload` | 刷新当前标签页。 |
| `back` | 当前标签页后退。 |
| `forward` | 当前标签页前进。 |
| `status` | 输出当前活动标签页、URL、加载状态和可前进/后退状态。 |
| `tabs` | 输出所有标签页。 |

示例：

```bash
openhouse-browser open http://127.0.0.1:30141/
openhouse-browser new-tab https://example.com
openhouse-browser tabs
openhouse-browser switch 0
```

### 页面读取和自动化

| 命令 | 用途 |
| --- | --- |
| `text` | 读取当前页面正文文本。 |
| `html` | 读取当前页面 HTML。 |
| `screenshot --output PATH` | 截图为 PNG，并把结果写入指定路径。 |
| `eval CODE` | 在当前页面执行 JavaScript。 |
| `eval-file FILE` | 从文件读取 JavaScript 后执行。 |
| `click SELECTOR` | 点击 CSS selector 匹配的元素。 |
| `fill SELECTOR VALUE` | 给输入框或可编辑元素填入文本，并触发 `input`/`change`。 |
| `wait selector SELECTOR --timeout MS` | 等待 CSS selector 出现。 |
| `wait-text TEXT --timeout MS` | 等待页面包含指定文本。 |
| `tap X Y` | 按坐标触摸当前 WebView。 |
| `type TEXT` | 向当前焦点元素输入文本。 |
| `scroll DX DY` | 滚动当前页面。 |
| `run FLOW_JSON` | 按 JSON 流程连续执行多步命令。 |

示例：

```bash
openhouse-browser wait selector '#app' --timeout 10000
openhouse-browser text
openhouse-browser click 'button[type=submit]'
openhouse-browser fill 'input[name=q]' 'OpenHouseAI'
openhouse-browser type 'hello'
openhouse-browser scroll 0 600
openhouse-browser screenshot --output /data/data/com.termux/files/home/.openhouse-browser/results/page.png
```

`text`、`html`、`click`、`fill` 和 `tap` 也支持通过底层 JSON RPC 指定 selector；CLI 已封装常用形式。`screenshot --output` 的输出路径必须在 App files 目录内，推荐写入 `.openhouse-browser/results/`。

### CDP 兼容子集

`openhouse-browser cdp METHOD JSON_PARAMS` 提供少量 Chrome DevTools Protocol 兼容方法，方便已有自动化工具复用基本能力。它不是完整 CDP server。

当前支持：

```text
Browser.getVersion
Target.getTargets
Target.activateTarget
Page.navigate
Page.reload
Page.captureScreenshot
Runtime.evaluate
DOM.getDocument
DOM.querySelector
DOM.getOuterHTML
Input.dispatchMouseEvent
Input.dispatchKeyEvent
```

示例：

```bash
openhouse-browser cdp Browser.getVersion '{}'
openhouse-browser cdp Page.navigate '{"url":"http://127.0.0.1:30141/"}'
openhouse-browser cdp Runtime.evaluate '{"expression":"document.title"}'
```

### JSON 流程

`run` 接收 JSON 数组或包含 `steps` 的对象。每一步都是一个受控浏览器命令对象。

```bash
openhouse-browser run '[
  {"command":"open","url":"http://127.0.0.1:30141/"},
  {"command":"wait","params":{"selector":"body"},"timeoutMs":10000},
  {"command":"text"}
]'
```

如果某一步失败，`run` 会停止后续步骤并返回失败步骤和已完成步骤的结果。

### RPC 文件和安全约束

底层通过 Android broadcast 发给 OpenHouse App：

```text
com.termux.app.browser.action.CONTROLLED_BROWSER_COMMAND
```

RPC 文件目录：

```text
$HOME/.openhouse-browser/requests
$HOME/.openhouse-browser/results
$HOME/.openhouse-browser/token
```

自动化命令会使用 token；请求和结果路径必须位于 `.openhouse-browser/requests` 或 `.openhouse-browser/results` 之下。AI agent 不应绕过这些路径限制，也不要把 token 写入聊天回复、日志或共享文档。

受控浏览器适合短时页面检查、表单填写、截图和自动化验证。长期服务、模型进程、MCP server 或后台任务仍必须交给 service-manager 管理，不要用 WebView 或浏览器命令长期拉起。

## WebView 和外部浏览器差异

可能出现的差异包括：

- WebView 版本较旧，不支持某些新浏览器 API。
- 安全策略、cookie、localStorage 或跨域行为不同。
- 输入法、软键盘、视口高度和滚动行为不同。
- 文件选择、下载和上传体验不同。
- 某些页面在外部浏览器可用，在 WebView 中显示空白或控件错位。

如果页面异常，AI 应先截图或询问用户看到的具体画面，再检查日志和网络请求。

## 调试顺序

1. 确认 service-manager 中对应服务是否运行。
2. 确认本地端口是否可访问。
3. 用 `curl` 检查首页是否返回。
4. 在外部浏览器和内置 WebView 中分别尝试。
5. 查看 Android logcat 或服务日志。
6. 若只有 WebView 异常，优先做前端兼容修复，而不是改服务端模型或配置。

示例：

```bash
curl -fsS --max-time 3 http://127.0.0.1:30141/ >/dev/null && echo ok
```

## 端口绑定

本地服务默认应绑定 `127.0.0.1` 或明确的 Tailscale IP，不要默认绑定 `0.0.0.0`。如果用户要求在 Tailscale 网络访问，应使用当前设备 Tailscale 地址，并确认访问范围。

## 给用户的说明

当页面打不开或白屏时，不要只说“刷新”。应说明：

- 服务是否正在运行。
- 本地端口是否可达。
- 外部浏览器是否正常。
- WebView 是否存在兼容问题。
- 下一步是重启服务、刷新页面、清理页面状态，还是修复前端代码。
