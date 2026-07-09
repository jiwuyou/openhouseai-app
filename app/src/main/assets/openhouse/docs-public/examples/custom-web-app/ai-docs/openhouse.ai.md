# Hello OpenHouse

`hello-openhouse` 是 OpenHouse 自定义 Web App 的最小示例。

AI 使用规则：

- 打开页面使用 `smallphoneApp.entry.url` 或 `shellMenu.entry.url`。
- 启动、停止、重启、日志和修复都通过 `service-manager://services/hello-openhouse`。
- 不要从组件 manifest 推断 shell 命令；命令只存在于 service-manager 服务定义中。
- 用户数据在 App 的 `data/` 目录中，更新代码时不要删除。

