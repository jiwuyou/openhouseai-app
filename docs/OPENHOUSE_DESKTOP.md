# OpenHouse Native Desktop

本文档说明 OpenHouse 的原生桌面壳设计。这里不再把“首页”写成某个固定页面，而是写成“默认入口”：每次打开 OpenHouse 后要进入哪里。

## Default Entry Model

OpenHouse 的默认入口可以指向：

| Entry | Role |
| --- | --- |
| 桌面 | 推荐默认值。打开 OpenHouse 后进入原生桌面壳。 |
| 某个桌面 App | 直接打开用户指定的 App，例如 SmallPhone、pi-agent、AionUi、cc/codex、Operit 或服务控制页。 |
| 上次退出页 | 恢复上次离开 OpenHouse 时所在页面；目标失效时 fallback 到桌面。 |

旧的“首页/菜单”功能不删除，但不再承担默认菜单心智。它应作为桌面里的一个普通 App，例如“菜单总览”，用于承接安装、状态、维护、服务控制等既有入口和 fallback 价值。

## Desktop Shell

桌面页是 Android 原生 UI，不是全 WebView 多窗口系统。桌面只负责展示入口、记录布局和分发启动动作：

- 桌面图标只显示图标和名称。
- 默认不显示运行状态角标、红点、绿点或常驻监控文字。
- 桌面按固定网格横向分页，用户左右滑动切换页面。
- 桌面只保存入口元数据、顺序、页位置、显示名 override 和图标 override。
- 桌面不预创建 WebView，不常驻多个 WebView，也不做重叠多窗口。
- WebView、native page、terminal、service-control、android activity 都可以被包装成桌面 App。

这个设计的目的，是让用户获得桌面式入口，同时保留 Android 原生层的稳定性和恢复能力。

## Desktop Editing

桌面有普通模式和编辑模式：

| Mode | Behavior |
| --- | --- |
| 普通模式 | 点击 App 打开；左右滑动翻页；桌面不显示顶部控制栏。 |
| 编辑模式 | 长按空白处或图标进入；图标可拖动排序；可改名、改图标、隐藏、重置、设为默认入口或进入状态/详情。 |

图标拖动后的顺序和分页位置必须持久化。布局以稳定 App id 为 key 保存；某个 flavor 或版本中缺失的 App 要自动跳过，新增 App 要自动追加。

“改名”只改变桌面显示名，不改变底层组件标题或 service id。“改图标”优先使用轻量 icon key、文字、符号或颜色，不默认导入大图片，避免增加常驻内存。

## Launch And Recovery

桌面 App 的基础交互规则是：

| Action | Behavior |
| --- | --- |
| 点击 | 打开 App。必要时先检查或启动关联服务，再进入目标页面。 |
| 长按 | 进入桌面编辑模式，编辑弹窗可进入 App 状态/详情。 |
| 打开失败 | 自动弹出状态面板，而不是只停在空白 WebView 或无限加载。 |

状态面板可以显示：

- App id、标题、入口类型
- WebView URL 或 native page id
- service-manager service id / service ref
- 当前服务状态、pid、最近错误或日志摘要
- 打开、启动、停止、重启、查看日志、服务控制、维护中心等操作

状态信息不占用桌面图标；它只在 App 详情、打开失败、全局状态入口或 App 页控制面中出现。

## App Control Bar

桌面和 App 页的控制面不同：

- 在桌面页，不显示顶部“浏览器打开 / 控制 / 刷新”等控制栏。
- 进入某个 App 后，显示顶部控制栏，用于返回桌面、刷新、外部浏览器打开、服务控制或状态查看。
- 顶部控制栏可以收起。收起后显示一个白色到黑色渐变的悬浮球。
- 点击悬浮球展开控制栏；桌面页不显示悬浮球。

这样桌面保持简洁，App 页仍保留 WebView/服务排障所需的显式控制。

## Entry Types

桌面 App 由组件 registry 提供。入口类型包括：

| Entry Type | Use |
| --- | --- |
| `webview` | pi-agent / pi-web、AionUi、SmallPhone frontend beta、cc/codex、受控浏览器等本地或受控网页入口。 |
| `native-page` | 菜单总览、安装引导、维护中心、日志、权限、高级设置等 Android 原生页。 |
| `terminal` | Termux 或 Ubuntu 终端入口。 |
| `service-control` | service-manager 控制页或某个服务的控制入口。 |
| `android-activity` | Android Activity 入口，例如 `withOperit` flavor 中的 Operit。 |

组件注册只描述 UI 入口、桌面展示和 service-manager 引用。长期服务的命令、工作目录、环境变量、停止方式和健康检查仍属于 service-manager 服务定义。

## Memory Policy

桌面常驻成本必须保持很低：

- 桌面只渲染 Android 原生 View 和轻量元数据。
- 横向分页只保留当前页和邻近页需要的 View。
- 点击 App 后才创建或加载 WebView/native page/terminal。
- 默认不保留多个后台 WebView；内存压力下应释放非当前 App 页面。
- 自定义图标默认使用轻量 icon key/文字/符号/颜色，不解码大图片常驻。

## Operit Flavor Boundary

Operit 是 Android 侧完整可选构建能力：

- `withOperit` 显示 Operit / AI朋友 Help 桌面 App，并通过 Android Activity/host bridge 打开。
- `withoutOperit` 不显示 Operit 桌面 App，不依赖 Operit feature module，也不硬引用 `com.ai.assistance.operit.*`。

两个 flavor 的包名都保持 `com.termux`，不能共存；发布时只能同签名、递增 `versionCode` 后互相升级或替换。

## Acceptance Rules

- 打开 OpenHouse 后的默认目标由默认入口策略决定，可选桌面、某个桌面 App 或上次退出页。
- 菜单总览必须保留为桌面 App，不能被桌面实现删除。
- 桌面 App 默认只显示图标和名称。
- 桌面支持横向分页、编辑模式、拖动排序、改名和改图标。
- 桌面隐藏顶部控制栏；App 页显示控制栏，并可收起为白黑渐变悬浮球。
- 打开失败自动弹状态面板。
- WebView App 不能要求桌面壳保持多个 WebView 常驻。
- with/without Operit 的桌面入口必须按 flavor 隔离。
