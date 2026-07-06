# OpenHouse 原生桌面

本文档说明 OpenHouse 的桌面页。这里的“首页”指每次打开 OpenHouse 后默认进入哪里，不等于某一个固定功能页。

## 页面关系

OpenHouse 同时保留：

| 页面 | 说明 |
| --- | --- |
| 桌面页 | 原生桌面壳，显示 App 图标网格，适合作为日常入口。 |
| 现有首页功能页 | 原来的首页/功能页，保留安装、状态、维护、服务控制等入口。 |

默认启动页可以设置为：

- 桌面页
- 现有首页功能页
- 上次退出时的页面

所以文档和 AI 操作说明应把“首页”理解为默认启动页策略；不要把桌面页和现有首页功能页写成同一个页面。

## 桌面是什么

桌面是 Android 原生 UI，不是全 WebView 多窗口系统。

- 图标只显示图标和名称。
- 默认没有状态角标、红点、绿点或常驻监控文字。
- 桌面不常驻多个 WebView。
- 每个桌面 App 可以对应 WebView、原生页、终端、服务控制或 Android Activity。

## 怎么使用

| 操作 | 行为 |
| --- | --- |
| 短按 App | 打开 App；必要时先检查或启动关联服务。 |
| 长按 App | 打开状态面板，查看状态、日志和恢复操作。 |
| 打开失败 | 自动弹出状态面板，提供重试、重启、日志、服务控制或维护入口。 |

状态面板用于排障，不占用桌面图标。桌面默认保持简洁。

## App 类型

桌面 App 可以是：

- `webview`：pi-agent / pi-web、AionUi、SmallPhone frontend beta、cc/codex、受控浏览器。
- `native-page`：安装引导、现有首页功能页、维护中心、日志、权限、高级设置。
- `terminal`：Termux 或 Ubuntu 终端。
- `service-control`：service-manager 服务控制。
- `android-activity`：Android Activity，例如 `withOperit` 中的 Operit。

组件注册只描述入口、桌面展示和 service-manager 引用；后台服务的命令、环境变量、停止方式和健康检查仍由 service-manager 管理。

## Operit

Operit 只在 `withOperit` flavor 中显示为桌面 App。`withoutOperit` 不显示 Operit，不依赖 Operit，也不硬引用 Operit 类。

两个 flavor 的包名都保持 `com.termux`，不能共存；只能同签名、递增 `versionCode` 后互相升级或替换。
