# 停止运行栈和全部退出 OpenHouse

本文定义 OpenHouse 的“停止运行栈”和“全部退出 OpenHouse”语义。它们不是普通返回键，也不是把 App 放到后台；它们是用户主动要求停止 OpenHouse 运行栈，让 CPU 和内存占用尽可能回到空闲。

## 用户目标

用户点击“停止运行栈”或“全部退出 OpenHouse”时，系统应理解为：

```text
我现在不想让 OpenHouse、Termux、Ubuntu 里的 OpenHouse 服务继续占用 CPU 和内存。
请停止 OpenHouse 管理的长期服务和控制中枢。
如果我选择停止运行栈，请保留当前 OpenHouse 界面，并在本次 App 会话内暂停自动保活。
如果我选择全部退出 OpenHouse，请关闭 OpenHouse 界面，并请求关闭 Termux 前台服务和终端会话。
保留我的文件、配置和日志，方便下次打开恢复。
```

## 必须停止的范围

停止运行栈必须停止：

- service-manager 管理的所有长期服务。
- 默认长期服务：`smallphone`、`pi-agent`、`cloudcli`。
- `pi-agent` 背后的 `pi-web` 或其它内部运行时服务。
- `openhouse-connect` 或同类连接桥接服务。
- OpenHouse 注册并拉起的 MCP server、agent server、本地 Web UI。
- service-manager 本身。
- OpenHouse 拉起的 Termux 长期进程。
- OpenHouse 拉起的 Ubuntu 长期进程。

如果某个服务是用户后来添加并注册为长期服务，也应被停止运行栈和全部退出 OpenHouse 停止。

全部退出 OpenHouse 在停止运行栈后，还会：

- 关闭 OpenHouse 界面。
- 请求关闭 Termux 前台服务和终端会话。

注意：Termux 里非 OpenHouse 管理的用户任务不应被文档承诺统一停止。如果用户明确需要关闭其它个人任务，应单独处理。

## 不应停止的范围

停止运行栈不应终止：

- Android 系统进程。
- 不是 OpenHouse 拉起的用户个人进程。
- 用户手动在终端里启动且未注册到 OpenHouse 的实验进程，除非用户明确要求。
- Termux App 安装本身。
- Ubuntu rootfs。
- 用户项目。
- 模型配置。
- API 配置。
- 日志。
- 已下载 payload。

不要使用不加筛选的大范围 `killall` 或 `pkill`。只能停止明确属于 OpenHouse 管理范围的服务和进程。

## 必须保留的数据

停止运行栈和全部退出 OpenHouse 必须保留：

| 数据 | 示例 |
| --- | --- |
| 用户项目 | `/root/projects`、`/root/workspace`、用户自定义目录。 |
| OpenHouse 文档 | `/root/openhouse/docs`。 |
| pi-agent / pi-web 数据 | `/root/.pi` 及相关本机状态。 |
| service-manager 配置 | `$HOME/.config/openhouseai/service-manager`。 |
| 组件注册 | `$HOME/.config/openhouseai/components.d`。 |
| Claude / Codex / CloudCLI 配置 | 用户本机私有配置。 |
| 日志 | 安装日志、运行日志、修复日志。 |
| Termux / Ubuntu 环境 | Termux prefix、Ubuntu rootfs、已安装软件。 |

停止运行栈和全部退出 OpenHouse 都不是清理数据、不是重置系统、不是重装环境。

## 推荐执行顺序

1. UI 进入“正在停止运行栈”或“正在全部退出 OpenHouse”状态，暂停前台保活。
2. 通过 service-manager 停止所有已注册长期服务。
3. 确认 `smallphone`、`pi-agent`、`cloudcli`、`openhouse-connect` 已停止。
4. 停止 service-manager 本身。
5. 检查 OpenHouse 拉起的 Termux/Ubuntu 残留长期进程。
6. 如果用户选择“全部退出 OpenHouse”，关闭 OpenHouse 界面，并请求关闭 Termux 前台服务和终端会话。
7. 只清理明确属于 OpenHouse 且无法正常停止的残留进程。
8. UI 显示“未运行”。

如果 service-manager 不可用，应走安全 fallback：

- 读取已知服务注册信息。
- 只停止已知 OpenHouse 服务。
- 不扫描和终止无关用户进程。
- 给出“控制中枢不可用，已执行安全退出”的提示。

## 再次打开 App

停止运行栈后，当前 App 界面保留，自动保活在本次 App 会话内暂停。用户可以在当前界面点击“恢复默认核心服务”重新拉起。全部退出 OpenHouse 后，再次打开 App 时：

1. Android 侧检测 service-manager 是否运行。
2. 如果未运行，启动 service-manager。
3. service-manager 读取注册服务。
4. 按前台运行策略恢复默认长期服务。
5. UI 显示 `运行中` 或具体错误。

如果用户已在高级设置中关闭自动保活，再次打开 App 时只展示状态和修复入口，不应自动拉起 service-manager。

用户不需要手动进入终端恢复。

## UI 要求

入口名称建议使用：

```text
停止运行栈
```

```text
全部退出 OpenHouse
```

确认说明建议包含：

```text
这会停止 OpenHouse 管理的后台服务和控制中枢，释放 CPU 和内存。
全部退出 OpenHouse 还会关闭 OpenHouse 界面，并请求关闭 Termux 前台服务和终端会话。
你的文件、模型配置、项目和日志会保留。
下次打开 App 时会自动恢复核心服务。
```

完成后状态：

```text
OpenHouse 状态：未运行
```

失败时必须提供：

- 重试停止运行栈或全部退出 OpenHouse。
- 查看详情。
- 修复运行控制。

## 安全与日志

停止运行栈和全部退出 OpenHouse 日志只记录：

- 停止了哪些服务 ID。
- 哪些服务停止成功。
- 哪些服务停止失败。
- 是否存在残留 OpenHouse 进程。
- 下一步建议。

不得记录完整：

- `key/token`
- auth token
- provider secret
- service-manager token
- 用户输入的模型凭据

## 验收标准

- 点击停止运行栈后，默认长期服务停止，当前 App 界面保留。
- 点击停止运行栈后，本次 App 会话暂停自动保活，直到用户恢复默认核心服务或重新打开 App。
- 点击全部退出 OpenHouse 后，默认长期服务停止，OpenHouse 界面关闭，并请求关闭 Termux 前台服务和终端会话。
- service-manager 本身停止。
- OpenHouse 拉起的 Termux/Ubuntu 长期进程停止。
- 用户项目、模型配置、日志和 payload 保留。
- 不终止无关用户进程。
- UI 进入 `未运行` 状态。
- 再次打开 App 能按前台策略恢复核心服务。
- 退出日志不泄露 `key/token`。
