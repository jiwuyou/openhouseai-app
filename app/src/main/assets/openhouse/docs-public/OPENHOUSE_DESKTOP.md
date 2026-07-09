# OpenHouse 原生桌面

本文档说明 OpenHouse 的桌面页。这里不再把“首页”写成固定页面，而是写成“默认入口”：每次打开 OpenHouse 后要进入哪里。

## 默认入口

默认入口可以设置为：

- 桌面。
- 某个桌面 App，例如 SmallPhone、pi-agent、AionUi、cc/codex、Operit 或服务控制。
- 上次退出时的页面；目标失效时回到桌面。

旧的首页/菜单功能不会删除，但它不再承担默认菜单心智。它保留为桌面里的“菜单总览”App，用于进入安装、状态、维护、服务控制等既有功能。

## 桌面是什么

桌面是 Android 原生 UI，不是全 WebView 多窗口系统。

- 图标只显示图标和名称。
- 默认没有状态角标、红点、绿点或常驻监控文字。
- 桌面按固定网格横向分页，左右滑动切换页面。
- 桌面布局是稀疏槽位模型，保存每个 App 的 `pageIndex` 和 `slotIndex`；空位可以长期保留，不会自动压紧。
- 桌面保存入口元数据、槽位位置、显示名 override 和图标 override。
- 桌面不预创建多个 WebView，不要求多个 WebView 常驻。
- 每个桌面 App 可以对应 WebView、原生页、终端、服务控制或 Android Activity。

## 怎么使用

| 操作 | 行为 |
| --- | --- |
| 点击 App | 打开 App；必要时先检查或启动关联服务。 |
| 长按空白处或 App | 进入编辑模式。 |
| 打开失败 | 自动弹出状态面板，提供重试、重启、日志、服务控制或维护入口。 |

编辑模式支持拖动排序、跨屏拖动、改名、改图标、隐藏、重置、设为默认入口和进入 App 状态/详情。改名只改变桌面显示名；改图标优先使用轻量 icon key、文字、符号或颜色，不默认导入大图片。

桌面图标不需要紧凑排列。拖到空槽位时移动图标并留下原空位；拖到已占槽位时可以交换；拖到最后一屏外侧时创建新屏，并把图标放入新屏目标槽位。拖动后的页和槽位会保存。

## 顶部控制栏

- 在桌面页，不显示顶部控制栏。
- App WebView 页壳不重复显示 App 名；App 名只用于桌面、列表、服务、状态和无障碍。
- 页壳保留 `侧栏 / 桌面 / 刷新 / 收起 / 控制 / 用浏览器打开`。
- `侧栏` 打开全局 Drawer；`桌面` 返回原生桌面；`刷新` 只刷新当前 App；`控制` 打开当前 App 的状态、日志、服务控制和恢复动作；`用浏览器打开` 用于在浏览器中排查当前 URL。
- 控制栏可以通过 `收起` 收起；收起后显示白色到黑色渐变的悬浮球。
- 悬浮球可拖动，松手后吸附到左边或右边，并保存位置；点击悬浮球可以重新展开控制栏；桌面页不显示悬浮球。

## WebView 保留策略

- WebView 默认保留最近 2 个，可配置范围是 0-5。
- 同 App/URL 优先复用已有 WebView。
- 超出保留数量后按 LRU 清理。
- 保留 WebView 不是后台无限运行；长期服务仍由 service-manager 管理。
- App 必须能从持久化状态恢复，不能依赖 WebView 一直存活。

## App 类型

桌面 App 可以是：

- `webview`：pi-agent / pi-web、AionUi、SmallPhone frontend beta、cc/codex、受控浏览器。
- `native-page`：菜单总览、安装引导、维护中心、日志、权限、高级设置。
- `terminal`：Termux 或 Ubuntu 终端。
- `service-control`：service-manager 服务控制。
- `android-activity`：Android Activity，例如 `withOperit` 中的 Operit。

组件注册只描述入口、桌面展示和 service-manager 引用；后台服务的命令、环境变量、停止方式和健康检查仍由 service-manager 管理。

## Operit

Operit 只在 `withOperit` flavor 中显示为桌面 App。`withoutOperit` 不显示 Operit，不依赖 Operit，也不硬引用 Operit Java/Kotlin 包名。

两个 flavor 的包名都保持 `com.termux`，不能共存；只能同签名、递增 `versionCode` 后互相升级或替换。
