# OpenHouse Native Desktop

本文档说明 OpenHouse 的原生桌面壳设计。这里的“首页”指每次打开 OpenHouse 后默认进入的页面策略，不等同于某一个固定功能页。

## Page Model

OpenHouse 同时保留两个前台页面概念：

| Page | Role |
| --- | --- |
| 桌面页 | 原生桌面壳，展示 OpenHouse App 图标网格，是默认日常入口候选。 |
| 现有首页功能页 | 原来的首页/功能页，保留安装、状态、维护、服务控制等既有入口和 fallback 价值。 |

启动默认页面可以设置为：

- 桌面页
- 现有首页功能页
- 上次退出时的页面

因此“首页”应写成“默认启动页策略”或“打开 OpenHouse 后进入的页面”。不要把桌面页和现有首页功能页混为同一个页面，也不要为了桌面化删除现有首页功能。

## Desktop Shell

桌面页是 Android 原生 UI，不是全 WebView 多窗口系统。桌面只负责统一展示和分发入口：

- 图标网格只显示图标和名称。
- 默认不显示运行状态角标、红点、绿点或常驻监控文字。
- 桌面不常驻多个 WebView，不做重叠多窗口。
- WebView、native page、terminal、service-control、android activity 都可以被包装成桌面 App。

这个设计的目的，是让用户获得桌面式入口，同时保留 Android 原生层的稳定性和恢复能力。

## Interaction

桌面 App 的交互规则固定为：

| Action | Behavior |
| --- | --- |
| 短按 | 打开 App。必要时先检查或启动关联服务，再进入目标页面。 |
| 长按 | 打开状态面板，查看状态、日志和恢复操作。 |
| 打开失败 | 自动弹出同一个状态面板，而不是只停在空白 WebView 或无限加载。 |

状态面板可以显示：

- App id、标题、入口类型
- WebView URL 或 native page id
- service-manager service id / service ref
- 当前服务状态、pid、最近错误或日志摘要
- 打开、启动、停止、重启、查看日志、服务控制、维护中心等操作

桌面图标保持干净；状态信息只在长按、打开失败或全局状态入口里出现。

## Entry Types

桌面 App 由组件 registry 提供。入口类型包括：

| Entry Type | Use |
| --- | --- |
| `webview` | pi-agent / pi-web、AionUi、SmallPhone frontend beta、cc/codex、受控浏览器等本地或受控网页入口。 |
| `native-page` | 安装引导、现有首页功能页、维护中心、日志、权限、高级设置等 Android 原生页。 |
| `terminal` | Termux 或 Ubuntu 终端入口。 |
| `service-control` | service-manager 控制页或某个服务的控制入口。 |
| `android-activity` | Android Activity 入口，例如 `withOperit` flavor 中的 Operit。 |

组件注册只描述 UI 入口、桌面展示和 service-manager 引用。长期服务的命令、工作目录、环境变量、停止方式和健康检查仍属于 service-manager 服务定义。

## Operit Flavor Boundary

Operit 是 Android 侧完整可选构建能力：

- `withOperit` 显示 Operit / AI朋友 Help 桌面 App，并通过 Android Activity/host bridge 打开。
- `withoutOperit` 不显示 Operit 桌面 App，不依赖 Operit feature module，也不硬引用 `com.ai.assistance.operit.*`。

两个 flavor 的包名都保持 `com.termux`，不能共存；发布时只能同签名、递增 `versionCode` 后互相升级或替换。

## Acceptance Rules

- 打开 OpenHouse 后的默认目标由启动页策略决定，可选桌面、现有首页功能页或上次退出页。
- 桌面页不能删除或覆盖现有首页功能页。
- 桌面 App 默认只显示图标和名称。
- 短按打开，长按状态面板，打开失败自动弹状态面板。
- WebView App 不能要求桌面壳保持多个 WebView 常驻。
- with/without Operit 的桌面入口必须按 flavor 隔离。
