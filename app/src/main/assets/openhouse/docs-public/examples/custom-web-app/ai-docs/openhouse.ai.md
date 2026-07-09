# Hello OpenHouse

`hello-openhouse` 是 OpenHouse 自定义 Web App 的最小示例。

AI 使用规则：

- 打开页面使用 `smallphoneApp.entry.url` 或 `shellMenu.entry.url`。
- 优先用 CLI 或 MCP 执行结构化动作；CLI 默认输出 JSON，适合 AI、脚本和人共同使用。
- 启动、停止、重启、日志和修复都通过 `service-manager://services/hello-openhouse`。
- 不要从组件 manifest 推断 shell 命令；命令只存在于 service-manager 服务定义中。
- 用户数据在 App 的 `data/` 目录中，更新代码时不要删除。

控制入口：

- 手机 WebView：`http://127.0.0.1:23110/`
- HTTP API：`GET /health`、`GET /api/state`、`POST /api/tasks`、`DELETE /api/tasks/:id`
- CLI 本地命令模式：`demo-hello-openhouse health`、`demo-hello-openhouse state`、`demo-hello-openhouse list`、`demo-hello-openhouse add "<title>"`、`demo-hello-openhouse delete <id>`
- CLI HTTP 模式：`demo-hello-openhouse --url http://127.0.0.1:23110 state`、`demo-hello-openhouse --url http://127.0.0.1:23110 add "<title>"`
- MCP stdio：`node /root/smallphoneai-repos/smallphone-home/apps/hello-openhouse/src/mcp-server.js`
- MCP tools：`hello_openhouse_health`、`hello_openhouse_state`、`hello_openhouse_list_tasks`、`hello_openhouse_add_task`、`hello_openhouse_delete_task`

CLI 命名规则是 `<namespace>-<app-id>`，避免未来应用市场里不同作者的命令冲突。真实 App 应把 `demo` 换成用户、团队或厂商自己的短命名。

Web API、CLI 和 MCP 都复用 `src/state.js`，因此 AI 更新 App 时应优先修改共享业务模块，再让三个入口调用同一批函数。
