# Hello OpenHouse Custom Web App

这是 `CUSTOM_FRONTEND_AND_APPS.md` 配套的最小可运行自定义 App。

特点：

- Node.js 标准库实现，无第三方依赖。
- `GET /health` 给 service-manager 做健康检查。
- `GET /api/state`、`POST /api/tasks`、`DELETE /api/tasks/:id` 给前端演示数据读写。
- `demo-hello-openhouse` CLI 默认输出 JSON，给 AI、脚本和人共同使用；支持本地命令模式和 HTTP 模式。
- `src/mcp-server.js` 提供最小 MCP stdio server，给支持 MCP 的 AI 发现和调用同一组动作。
- Web API、CLI 和 MCP 共用 `src/state.js`，避免三套业务逻辑。
- `register-openhouse.sh` 会复制代码到 `SMALLPHONE_HOME/apps/hello-openhouse`，并通过 service-manager `/api/v1/registry/apply` 注册组件、服务和 AI 文档。

运行：

```bash
cd /root/openhouse/docs/examples/custom-web-app
bash register-openhouse.sh
```

默认地址：

```text
http://127.0.0.1:23110/
```

CLI：

```bash
demo-hello-openhouse state
demo-hello-openhouse add "让 AI 和用户都能控制这个 App"
demo-hello-openhouse list
demo-hello-openhouse delete <task-id>
demo-hello-openhouse --url http://127.0.0.1:23110 state
demo-hello-openhouse --url http://127.0.0.1:23110 add "通过 HTTP 添加"
```

默认终端命令名是 `demo-hello-openhouse`，格式是 `<namespace>-<app-id>`。生成真实用户 App 时，把 `demo` 换成用户、团队或厂商自己的短命名，例如 `alice-hello-openhouse`。安装时可用 `OPENHOUSE_CLI_NAMESPACE` 或 `OPENHOUSE_APP_CLI_NAME` 覆盖；命令名只使用字母、数字、点、下划线和短横线。

MCP：

```bash
node /root/smallphoneai-repos/smallphone-home/apps/hello-openhouse/src/mcp-server.js
```

MCP tools:

- `hello_openhouse_health`
- `hello_openhouse_state`
- `hello_openhouse_list_tasks`
- `hello_openhouse_add_task`
- `hello_openhouse_delete_task`

本地检查：

```bash
npm run check
```
