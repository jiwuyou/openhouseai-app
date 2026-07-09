# Hello OpenHouse Custom Web App

这是 `CUSTOM_FRONTEND_AND_APPS.md` 配套的最小可运行自定义 App。

特点：

- Node.js 标准库实现，无第三方依赖。
- `GET /health` 给 service-manager 做健康检查。
- `GET /api/state`、`POST /api/tasks`、`DELETE /api/tasks/:id` 给前端演示数据读写。
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

