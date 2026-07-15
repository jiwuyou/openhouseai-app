# OpenHouse 系统检查手册

`openhouse-system` 是 OpenHouse 的 subject 级系统检查命令。它把一个用户可见能力关联到明确的服务、入口、路径和技能，再按声明执行检查。它不替代 service-manager：service-manager 管理显式服务 ID，`openhouse-system` 负责把多个底层服务和非服务检查汇总成一个可读结果。

## 前置条件

命令运行在 Termux native 层，依赖 `jq` 和 `curl`。APK 首装应在 Termux 基础包阶段准备它们；旧设备或手工环境缺失时执行：

```bash
pkg install -y jq curl
command -v jq
jq --version
command -v openhouse-system
```

如果 `jq` 不存在，`validate`、`render`、`describe` 和 `check` 都会明确失败，不能把该次系统检查判为通过。

默认路径：

```text
subject 定义：$HOME/.config/openhouseai/subjects.d/*.json
渲染结果：  $HOME/.config/openhouseai/system/index.json
             $HOME/.config/openhouseai/system/index.md
服务配置：  $HOME/.config/openhouseai/service-manager/config.json
```

可使用以下环境变量覆盖：

```text
OPENHOUSEAI_HOME
OPENHOUSEAI_SUBJECTS_DIR
OPENHOUSEAI_SYSTEM_DIR
SERVICE_MANAGER_URL
SERVICE_MANAGER_TOKEN
SERVICE_MANAGER_CONFIG_PATH
OPENHOUSE_SYSTEM_CALLER_RUNTIME
```

## 四个正式命令

### 校验 subject 定义

```bash
openhouse-system validate
```

该命令检查所有 `subjects.d/*.json` 的基础结构和重复 ID。成功时输出：

```text
ok: <数量> subject(s) validated
```

没有 subject 文件时会给出警告；字段结构非法或 ID 重复时返回非零。修改或部署 subject 后，应先执行这个命令。

### 渲染系统索引

```bash
openhouse-system render
```

`render` 会先执行同等强度的校验，然后生成机器可读的 `index.json` 和人类可读的 `index.md`。生成文件是派生产物；事实源始终是 `subjects.d/*.json`。

### 查看一个 subject

```bash
openhouse-system describe service-control
openhouse-system describe pi-agent
```

`describe` 返回对应 subject 的完整 JSON，适合在检查前确认它声明了哪些 `serviceRefs`、入口、位置和非服务检查。

### 检查一个 subject

```bash
openhouse-system check service-control
openhouse-system check pi-agent
```

检查分成两阶段：

1. 逐个读取 `serviceRefs`，通过 service-manager REST API 查询服务状态和少量日志。
2. 只有服务阶段通过，才执行 `checks.afterServiceOk` 中的 HTTP、路径和技能检查。

典型顶层状态：

| 状态 | 含义 |
| --- | --- |
| `ok` | 服务阶段通过，非服务检查全部为 `ok` 或 `skipped`。 |
| `degraded` | 服务运行正常，但至少一个非服务检查为 `failed`、`timeout` 或 `error`。 |
| `service_issue` | 服务查询失败或某个服务不是 `running`；非服务检查不会继续执行，命令返回 2。 |

`skipped` 只表示当前检查类型或运行层无法执行，不是“已验证成功”。顶层为了允许能力降级会把 `skipped` 视为非阻断，但 AI 汇报时必须明确列出跳过项，不得声称该能力已经真实测通。

从 Ubuntu 或其它调用层触发时，可以显式记录调用者：

```bash
openhouse-system --caller-runtime ubuntu check pi-agent
```

`callerRuntime` 只进入结果用于诊断，不会改变 subject 声明的目标 runtime。

## subject 定义

最小结构：

```json
{
  "id": "example-app",
  "title": "Example App",
  "kind": "runtime-http",
  "summary": "Example local application.",
  "serviceRefs": [
    {
      "id": "example-app",
      "runtime": "termux",
      "manager": "service-manager"
    }
  ],
  "entries": [
    {
      "type": "web",
      "label": "Example App",
      "url": "http://127.0.0.1:23110/"
    }
  ],
  "locations": [
    {
      "runtime": "termux",
      "path": "/data/data/com.termux/files/home/.local/lib/example-app",
      "purpose": "installed runtime"
    }
  ],
  "ai": {
    "description": "What this subject provides.",
    "whenUnavailable": "Inspect service state and logs before changing files."
  },
  "checks": {
    "serviceTimeoutSeconds": 5,
    "afterServiceOk": [
      {
        "type": "http",
        "url": "http://127.0.0.1:23110/health",
        "timeoutSeconds": 4
      },
      {
        "type": "directoryExists",
        "runtime": "termux",
        "path": "/data/data/com.termux/files/home/.local/lib/example-app",
        "timeoutSeconds": 3
      }
    ]
  }
}
```

字段规则：

- `id` 必须稳定且唯一。
- `serviceRefs` 可以是服务 ID 字符串，也可以是包含 `id`、`runtime`、`manager` 的对象。
- 一个用户能力依赖多个服务时，应完整列入 `serviceRefs`。例如服务控制 subject 若只写 health URL 而没有 service refs，只能证明端口可访问，不能证明它所负责的业务服务状态正确。
- `entries` 和 `locations` 用于索引与解释，不会自动启动进程。
- `checks.afterServiceOk` 只在全部服务检查通过后运行。

## 支持的非服务检查

| `type` | 必填字段 | 行为 |
| --- | --- | --- |
| `http` | `url` | 使用 `curl` 请求 URL；区分 `ok`、`failed` 和 `timeout`。 |
| `pathExists` | `path` | 目标可以是文件或目录。 |
| `fileExists` | `path` | 目标必须是普通文件。 |
| `directoryExists` | `path` | 目标必须是目录。 |
| `skill` / `skillRegistered` | `id` 或 `path` | 按显式路径，或 Codex 本地 skill 目录查找 `SKILL.md`。 |

路径检查支持：

- `runtime: "termux"`、`"local"` 或 `"android"`：在当前 Termux shell 中检查。
- `runtime: "ubuntu"`：已在 Ubuntu 时直接检查；在 Termux 时通过 `proot-distro login ubuntu` 检查。
- `user` 与 `home`：用于明确 Ubuntu 用户及 `$HOME`/`~` 展开。
- `workingDirectory`、`working_dir` 或 `workdir`：先进入该目录再检查目标。

未支持的 runtime 或检查类型会产生 `skipped`。当前不支持任意 `command` 检查；不要用 `type: "command"` 伪装成功。需要检查命令产物时，优先声明可验证的 HTTP endpoint、文件、目录或 service-manager 服务。

## 首装和发布验收

首次安装或系统 subject 更新后，至少执行：

```bash
command -v jq
jq --version
openhouse-system validate
openhouse-system render
openhouse-system check service-control
openhouse-system check pi-agent
```

验收时还要确认：

- `service-control` 已配置能代表其职责的 `serviceRefs`，而不是只检查 `20087` health。
- service-manager token 能从 OpenHouse 专用配置读取，且没有出现在日志或回复中。
- `render` 生成的两个索引是合法、非空文件。
- 所有 `failed`、`timeout`、`error` 和 `skipped` 都在报告中逐项说明。

服务注册、状态、日志、生命周期控制和常驻策略的 REST API 见 `SERVICE_MANAGER.md`。正式路径是：

```text
/root/openhouse/docs/SERVICE_MANAGER.md
```
