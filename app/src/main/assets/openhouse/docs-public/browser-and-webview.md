# 内置浏览器和 WebView

这是稳定小写入口。完整说明见 `BROWSER_AND_WEBVIEW.md`。

OpenHouse 可以通过内置浏览器或 WebView 打开本机服务，例如 pi-web、CloudCLI、Hermes Web 或用户注册的其他工作台。排障时要区分：

- 服务是否真的在本机端口运行。
- WebView 是否因为兼容性、缓存、权限或前端兼容问题没有显示。
- 浏览器打开和 App 内 WebView 打开是否表现不同。

如果 WebView 异常，先用本机浏览器或 curl 验证服务，再看 Android 日志和前端控制台可见错误。
