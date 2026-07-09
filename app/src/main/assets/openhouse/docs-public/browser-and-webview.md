# 内置浏览器和 WebView

这是稳定小写入口。完整说明见 `BROWSER_AND_WEBVIEW.md`。

OpenHouse 可以通过内置浏览器或 WebView 打开本机服务，例如 pi-web、CloudCLI、Hermes Web 或用户注册的其他工作台。排障时要区分：

- 服务是否真的在本机端口运行。
- WebView 是否因为兼容性、缓存、权限或前端兼容问题没有显示。
- 浏览器打开和 App 内 WebView 打开是否表现不同。

如果 WebView 异常，先用本机浏览器或 curl 验证服务，再看 Android 日志和前端控制台可见错误。

## WebView 页壳

App WebView 外层页壳不重复显示 App 名；App 名只用于桌面、列表、服务/状态和无障碍。页壳保留侧栏、桌面、刷新、收起、控制和“用浏览器打开”等必要动作。

WebView 默认只保留最近 2 个，可配置范围是 0-5；同 App/URL 复用，超出后按 LRU 清理。保留不代表后台无限运行，App 必须能从持久化状态恢复。

## 受控浏览器

OpenHouse 的“受控浏览器”可以通过命令控制：

```bash
openhouse-browser --help
openhouse-browser open http://127.0.0.1:30141/
openhouse-browser tabs
openhouse-browser text
openhouse-browser screenshot --output /data/data/com.termux/files/home/.openhouse-browser/results/page.png
```

完整命令、JSON RPC、CDP 兼容子集和安全约束见 `BROWSER_AND_WEBVIEW.md` 的“受控浏览器命令”章节。
