# OpenHouse Memo Custom Web App

这是 `../../CUSTOM_FRONTEND_AND_APPS.md` 配套的最小可运行自定义 App。

特点：

- Node.js 标准库实现，无第三方依赖。
- `GET /health` 给 service-manager 做健康检查。
- `GET /api/state`、`POST /api/memos`、`DELETE /api/memos/:id` 给前端演示数据读写。
- `demo-memo-openhouse` CLI 默认输出 JSON，给 AI、脚本和人共同使用；支持本地命令模式和 HTTP 模式。
- `src/mcp-server.js` 提供最小 MCP stdio server，给支持 MCP 的 AI 发现和调用同一组动作。
- Web API、CLI 和 MCP 共用 `src/state.js`，避免三套业务逻辑。
- `register-openhouse.sh` 会复制代码到 `SMALLPHONE_HOME/apps/memo-openhouse`，并通过 service-manager `/api/v1/registry/apply` 注册组件、服务和 AI 文档。

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
demo-memo-openhouse state
demo-memo-openhouse add "这是一条备忘录"
demo-memo-openhouse list
demo-memo-openhouse delete <memo-id>
demo-memo-openhouse --url http://127.0.0.1:23110 state
demo-memo-openhouse --url http://127.0.0.1:23110 add "通过 HTTP 添加"
```

默认终端命令名是 `demo-memo-openhouse`，格式是 `<namespace>-<app-id>`。生成真实用户 App 时，把 `demo` 换成用户、团队或厂商自己的短命名，例如 `alice-memo-openhouse`。安装时可用 `OPENHOUSE_CLI_NAMESPACE` 或 `OPENHOUSE_APP_CLI_NAME` 覆盖；命令名只使用字母、数字、点、下划线和短横线。

MCP：

```bash
node /root/smallphoneai-repos/smallphone-home/apps/memo-openhouse/src/mcp-server.js
```

MCP tools:

- `memo_openhouse_health`
- `memo_openhouse_state`
- `memo_openhouse_list_memos`
- `memo_openhouse_add_memo`
- `memo_openhouse_delete_memo`

本地检查：

```bash
npm run check
```
