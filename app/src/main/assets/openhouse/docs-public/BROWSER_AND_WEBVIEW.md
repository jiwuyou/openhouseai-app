# 内置浏览器和 WebView

OpenHouse 可以通过内置浏览器或 Android WebView 打开本地网页服务，例如 pi-web、CloudCLI、Claude Code、Hermes Web 或其它用户安装的工作台。

WebView 与手机外部浏览器并不完全相同。调试页面问题时，AI 应同时考虑服务端状态、前端兼容性、WebView 限制和端口绑定。

OpenHouse 桌面页本身是原生桌面壳，不是全 WebView 多窗口。桌面 App 可以打开 WebView，但桌面不预创建多个 WebView，也不要求多个 WebView 常驻；WebView 主帧加载失败时，应回到桌面 App 的状态面板，提供重试、重启服务、日志和服务控制入口。桌面页隐藏顶部控制栏，进入 WebView App 后才显示 `左侧栏 / 桌面 / 当前 App 名 / 刷新 / 收起 / 右侧控制栏`；用户可以把控制栏收起为可拖动、吸附并持久化位置的白黑渐变悬浮球。

## 常见本地服务

| 服务 | 常见用途 | 说明 |
| --- | --- | --- |
| pi-web | pi-agent 背后的本地页面运行时 | 默认本地端口以 service-manager 注册为准 |
| CloudCLI / Claude Code | `cc/codex` 统一入口中的网页工作台 | 需要模型配置和服务连通 |
| Hermes Web | 可选高级工作台 | 安装后应注册到 service-manager |
| 用户自定义服务 | 开源工作台、知识库、项目管理系统 | 需要 service-manager 和侧边栏注册 |

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
