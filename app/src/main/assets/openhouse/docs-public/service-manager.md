# service-manager

本文是给下一轮实现、pi-agent、Claude Code、Codex 和维护人员使用的运行控制规格。完整历史说明可参考同目录的 `SERVICE_MANAGER.md`、`RECOVERY.md` 和 `ENVIRONMENT.md`。

## 定位

service-manager 是安装完成后的运行期控制平面。首次安装链路可以负责安装、解包、写入配置和首次启动；安装完成以后，后台服务的状态、启动、停止、重启、健康检查、日志和修复都应交给 service-manager。

不要把 service-manager 写成首次安装链路的前置控制器。安装链路的目标是把环境安装到足够完整，然后让 service-manager 接管运行期。

## 默认长期服务

默认长期运行集合至少包含：

| 服务 | 角色 | 说明 |
| --- | --- | --- |
| `service-manager` | 控制中枢 | 负责服务注册、状态、启动、停止、健康检查和修复。 |
| `smallphone` | SmallPhone 兼容服务 | 保持原 SmallPhone 能力和 Android 侧兼容入口。 |
| `pi-agent` | 首次配置助手 | 面向用户和 AI 的首次配置、文档检索、模型配置迁移入口。 |
| `cloudcli` | `cc/codex` 入口后端 | 承载 Claude Code / CloudCLI 相关网页入口，默认端口以服务定义为准，当前目标是 `23083`。 |

如果 `pi-agent` 背后需要单独的 `pi-web` 进程，它仍应由 service-manager 管理，但用户侧一级入口名称保持 `pi-agent`。不要把 pi-web 作为普通用户必须理解的额外大服务。

## 责任边界

service-manager 负责：

- 读取和维护服务列表。
- 使用稳定服务 ID 注册服务。
- 启动、停止、重启、修复服务。
- 维护服务健康状态。
- 暴露服务日志和运行状态。
- 给 Android 运行控制页提供真实状态。
- 给 OpenHouse 桌面、菜单总览 App 和侧边栏提供服务绑定。

service-manager 不负责：

- 首次下载安装所有文件。
- 替代 pi-agent 做模型配置说明。
- 替代 CloudCLI、Claude Code 或 Codex。
- 管理没有注册到 OpenHouse 的用户个人进程。
- 保存、打印或展示模型 `key/token`。

## 服务注册规则

所有 OpenHouse 长期服务必须注册到 service-manager。禁止用散落的 `nohup`、后台 shell、终端会话或 Android UI 直接长期拉起。

注册必须满足：

- 服务 ID 稳定，例如 `pi-agent`、`cloudcli`、`smallphone`。
- 同名服务只能有一个有效记录。
- 重复随机 ID 服务必须被清理或覆盖，避免 UI 显示正常但实际控制旧服务。
- 服务命令、环境变量、工作目录、健康检查写入 `ServiceSpec`。
- 桌面/侧边栏组件只写 UI 入口和 service-manager 引用，不写命令、脚本和参数。
- 服务应该以前台长进程形式运行，让 service-manager 能跟踪进程组。
- 健康检查必须能验证真实可用性，不能只判断 pid 存在。

推荐注册目录：

```text
$HOME/.config/openhouseai/service-manager/services.d/*.json
```

组件注册目录：

```text
$HOME/.config/openhouseai/components.d/*.json
```

## 新增服务长期运行规则

新增服务默认分为两类：

| 类型 | 默认行为 | 示例 |
| --- | --- | --- |
| 长期服务 | 注册到 service-manager，前台时自动保持运行。 | 新工作台、本地 Web UI、MCP server、agent server。 |
| 手动服务 | 注册到 service-manager，但默认不自动拉起。 | 临时实验服务、高耗电任务、大模型本地推理。 |

如果服务会影响用户主要入口、配置流程、AI 工作台或 Android 侧能力，默认按长期服务处理。如果服务 CPU、内存、网络或电量成本高，必须在文档和服务配置中明确标记为手动服务。

长期服务必须具备：

- 稳定服务 ID。
- 明确启动命令。
- 明确停止方式。
- 健康检查。
- 日志位置。
- 修复入口。
- 不泄露 `key/token` 的日志策略。

## Android 侧关系

Android 侧运行控制页应把 service-manager 显示为“控制中枢”。控制中枢不是普通业务服务；它是安装完成后的运行期控制平面。

Android 侧不应直接维护 `pi-agent`、`cloudcli`、`smallphone` 的内部进程细节。正确路径是：

```text
Android 前台检测 service-manager
service-manager 检查长期服务
service-manager 启动/停止/修复具体服务
Android 显示聚合状态和用户操作入口
```

UI 状态、service-manager 状态和端口健康状态必须一致。如果三者不一致，以实际健康检查为准，并提示用户进入运行修复。

## 状态与健康检查

服务状态至少区分：

- `not_installed`
- `stopped`
- `starting`
- `running`
- `unhealthy`
- `repairing`
- `failed`

健康检查优先级：

1. service-manager 自身 health API。
2. 服务注册表中稳定 ID 是否存在。
3. 服务运行状态。
4. 端口或 HTTP health 是否可达。
5. 关键命令是否可执行。
6. 最近日志是否存在明确错误。

不要只使用“文件存在”“pid 存在”判断服务成功。

## 安全与日志

service-manager、Android UI、pi-agent 和诊断报告不得打印完整：

- API key
- token
- auth token
- provider secret
- CloudCLI / Claude / Codex 凭据
- service-manager auth token

如果必须展示，必须脱敏成类似：

```text
sk-****abcd
token: ****abcd
```

禁止直接把包含 `env` 的完整服务 JSON 输出给用户或写入可分享诊断报告。需要排障时，只展示服务 ID、命令摘要、端口、健康检查 URL、状态和已脱敏环境变量。

## 验收标准

- 安装完成后 service-manager 可用，并显示为控制中枢。
- `smallphone`、`pi-agent`、`cloudcli` 能被 service-manager 真实启动、停止、重启。
- 服务使用稳定 ID，不出现同名随机重复服务。
- Android UI 状态、service-manager 状态、端口健康检查一致。
- 新增长期服务有 service-manager 注册、健康检查和日志。
- 运行日志和诊断信息不泄露 `key/token`。
- service-manager 不可用时，Android UI 提供运行修复入口，而不是让用户进入终端。
