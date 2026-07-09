# 自定义前端和 App

稳定入口：`CUSTOM_FRONTEND_AND_APPS.md`

运行期路径：

```text
/root/openhouse/docs/CUSTOM_FRONTEND_AND_APPS.md
```

这份文档包含两套可运行代码样例：

```text
/root/openhouse/docs/examples/custom-web-app
/root/openhouse/docs/examples/custom-phone-shell
```

技术栈默认建议：简单第一，默认使用场景是手机上的 OpenHouse / SmallPhone WebView；长期维护的正式 App 使用 TypeScript；能做出来时同时提供 WebView、CLI 和 MCP，让人和 AI 都能控制；CLI 同时支持本地命令和 HTTP 模式，终端命令名采用 `<namespace>-<app-id>` 以避免市场冲突；安装脚本、bootstrap 脚本和极小本地工具可以继续使用 shell 或原生 JavaScript。写新 App 前先按 `PATHS_AND_PORTS.md` 选择路径和端口，长期用户 App 默认使用 `23100-23999` 中未占用的端口，临时调试使用 `24000-24999`。完整规则见 `CUSTOM_FRONTEND_AND_APPS.md`。

当前 `custom-web-app` 示例是备忘录 App `memo-openhouse` / OpenHouse Memo，默认端口 `23110`，CLI 是 `demo-memo-openhouse`，MCP 工具使用 `memo_openhouse_*` 命名。
