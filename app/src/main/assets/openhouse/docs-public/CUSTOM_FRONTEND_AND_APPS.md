# 自定义前端和 App 编程指南

本文给用户、维护者和 AI agent 使用。目标是让 AI 可以真正写出一个可运行的自定义前端或 App，把它安装到首次 APK 确定的路径中，注册到 OpenHouse / SmallPhone 桌面，并交给 service-manager 管理长期进程。

本文根据以下代码和文档整理：

- `service-manager`：`/api/v1/registry/apply`、`components.d`、`service-manager/services.d`、`ServiceSpec`。
- `smallphone-active`：`openhouse-components/openhouse.ai.md`、`smallphone-app/packages/domain/component-registry.js`、`generic-mini-phone-beta/docs/backend-integration.md`。

完整可运行样例在：

```text
/root/openhouse/docs/examples/custom-web-app
/root/openhouse/docs/examples/custom-phone-shell
```

源码仓库中对应：

```text
app/src/main/assets/openhouse/docs-public/examples/custom-web-app
app/src/main/assets/openhouse/docs-public/examples/custom-phone-shell
```

## 先区分两种东西

| 类型 | 放在哪里 | 是否需要 service-manager | 典型入口 |
| --- | --- | --- | --- |
| 自定义 App | `SMALLPHONE_HOME/apps/<app-id>` | 需要，除非只是静态文件 | OpenHouse / SmallPhone 桌面图标打开 WebView |
| 自定义前端 shell | `SMALLPHONE_HOME/shells/<shell-id>` | 不需要单独服务，由 `smallphone-core` 托管 | 替换 SmallPhone 桌面壳 |

不要把两者混在一起。App 是被桌面打开的业务页面；shell 是桌面本身。

## 技术栈建议

这是默认建议，不是强制一刀切。目标是让新 App 容易维护，同时不把很小的本地工具做复杂。

第一原则是简单。能用一个清晰页面、一个本地服务和少量命令解决的问题，不要先堆复杂框架；只有 App 会长期维护、交互变复杂或多人协作时，再按下面的 TypeScript 建议升级。

默认使用场景是手机上的 OpenHouse / SmallPhone WebView，不是桌面浏览器。AI 生成 App 时应按手机优先设计：

- 第一屏在手机竖屏可直接完成主要操作。
- 控件适合触摸，按钮和输入框不要太小。
- 页面必须适配窄屏，文本不能溢出按钮、卡片或工具栏。
- 默认访问本机服务，例如 `http://127.0.0.1:<port>/`。
- 不假设用户会打开桌面浏览器、开发者控制台或命令行。
- App 页面不要假设外层页壳会显示 App 名；App 名只用于桌面、列表、服务/状态和无障碍，页面自己的标题由 App 自己设计。
- WebView 可能只保留最近少量窗口；App 必须能从持久化状态恢复，不能依赖 WebView 一直后台存活。

长期维护的 App 默认使用 TypeScript：

- 前端默认用 `Vite + TypeScript`。
- 复杂交互前端可以用 `React + TypeScript`。
- 后端默认用 `Node.js 24 LTS + TypeScript`。
- 包管理默认用 `npm`，避免让用户多理解 `pnpm`、`yarn` 等额外工具。
- 长任务默认用后端任务接口加 SSE 进度流。
- 能做出来时同时提供 WebView、CLI 和 MCP 三种入口，让人和 AI 都能控制同一套动作。

三种控制入口的分工：

- WebView：手机用户默认入口，适合点击、查看状态和完成主流程。
- CLI：AI、脚本和人都能使用的稳定命令入口；默认输出 JSON，方便 Codex / Claude Code 解析；应同时支持本地命令模式和 HTTP 模式。
- MCP：给支持 MCP 的 AI 工具发现能力和结构化调用，底层仍应复用 App 的同一套业务函数。

可以继续使用 shell 或原生 JavaScript 的情况：

- 安装、检查、注册、迁移、修复等一次性脚本，例如 `scripts/install.sh`、`scripts/check.sh`、`scripts/register-service.sh`。
- 非常小的本地工具页面，例如只有一个页面、几个按钮、状态检查和日志输出的工具。
- APK bootstrap、apt 安装、payload 解压、service-manager 注册等底层入口。

简单规则：

```text
长期维护的正式 App：默认 TypeScript。
默认使用场景：手机 WebView，手机优先设计。
控制入口：尽量同时提供 WebView、CLI、MCP。
安装脚本、bootstrap 脚本、极小本地工具：可以用 shell 或原生 JavaScript。
```

本文里的 `examples/custom-web-app` 使用原生 JavaScript，是为了提供最小可运行示例。AI 为用户生成新的长期 App 时，应优先生成 TypeScript 版本；只有在用户明确要求“越简单越好”或工具足够小时，才保留原生 JavaScript。

## 先选路径和端口

写新 App 前先阅读：

```text
/root/openhouse/docs/PATHS_AND_PORTS.md
```

新 App 不要随便占用 OpenHouse 控制平面、桥接、SmallPhone 平台服务或内置 App 端口。用户自定义长期 App 默认从 `23100-23999` 选择未使用端口；临时调试用 `24000-24999`；长期服务必须注册到 service-manager。本文示例是备忘录 App `memo-openhouse`，使用 `23110`。GitHub 配置助手 `github-config-helper` 是 APK 内置功能，不是自定义 App 示例，也不是自定义 App 模板。真实 App 应先检查当前 service-manager 和 component registry，再选择尚未被占用的端口。

## 路径约定

安装后的手机环境应优先使用这些稳定路径：

```text
/root/smallphoneai-repos/service-manager
/root/smallphoneai-repos/pi-agent
/root/smallphoneai-repos/pi-web
/root/smallphoneai-repos/openhouse-connect
/root/smallphoneai-repos/smallphone-active
/root/smallphoneai-repos/smallphone-home
```

开发机上可能仍有旧路径，例如 `/root/projects/smallphone/smallphone-active`。AI 写安装脚本时不要把开发机路径写死，应按下面方式解析：

```bash
REPOS_ROOT="${OPENHOUSE_REPOS_DIR:-/root/smallphoneai-repos}"
SMALLPHONE_ACTIVE="${SMALLPHONE_ACTIVE:-$REPOS_ROOT/smallphone-active}"
SMALLPHONE_HOME="${SMALLPHONE_HOME:-$REPOS_ROOT/smallphone-home}"
```

用户内容放在 `SMALLPHONE_HOME`，不要写进系统仓库：

```text
$SMALLPHONE_HOME/apps/<app-id>       # 用户 App 代码、SQLite、上传目录、数据
$SMALLPHONE_HOME/shells/<shell-id>   # 用户自定义前端 shell
```

系统代码可以由 AI 更新；用户目录必须保留。

## App 最小代码

完整代码见 `examples/custom-web-app`。它是一个无第三方依赖的 Node App：

```text
custom-web-app/
  package.json
  bin/memo-openhouse.js
  src/server.js
  src/state.js
  src/mcp-server.js
  public/index.html
  public/styles.css
  public/app.js
  openhouse.component.json
  service-manager.service.json
  register-openhouse.sh
  ai-docs/openhouse.ai.md
  ai-docs/capabilities.json
```

运行方式：

```bash
cd /root/openhouse/docs/examples/custom-web-app
bash register-openhouse.sh
```

脚本会把代码复制到：

```text
/root/smallphoneai-repos/smallphone-home/apps/memo-openhouse
```

然后调用 service-manager：

```text
POST /api/v1/registry/apply
POST /api/v1/services/memo-openhouse/start
```

成功后 App 地址是：

```text
http://127.0.0.1:23110/
```

OpenHouse / SmallPhone 桌面会通过组件注册读取入口。

## 人机控制入口

每个长期维护的 App 都应尽量把同一套能力暴露为三种入口：

| 入口 | 默认使用者 | 说明 |
| --- | --- | --- |
| 手机 WebView | 用户 | 放在 OpenHouse / SmallPhone 桌面上，第一屏完成主要操作。 |
| CLI | AI、脚本、人 | 默认输出 JSON，适合 Codex / Claude Code 调用，也方便用户在终端排障。 |
| MCP | AI | 给支持 MCP 的客户端发现工具并结构化调用。 |

WebView 入口只负责展示当前页面和承载用户交互。OpenHouse 外层页壳会提供侧栏、桌面、刷新、收起、控制和“用浏览器打开”等动作，但不会重复显示 App 名。外层可能只保留最近 `2` 个 WebView，配置范围是 `0-5`，并按同 App/URL 复用和 LRU 清理。自定义 App 必须把关键数据保存到后端、文件、SQLite、IndexedDB、localStorage 或可恢复 URL，不要依赖 WebView 一直留在后台。

CLI 也是给 AI 使用的，不只是给人手敲。AI 能用 CLI 时，不要绕过 App 的业务函数去直接改数据文件。CLI 应该调用和 Web API 相同的实现。

CLI 需要同时包含两种模式：

- 本地命令模式：直接调用 App 本地业务模块或数据目录，适合服务未启动、安装修复、迁移和离线维护。
- HTTP 模式：通过 `--url http://127.0.0.1:<port>` 调用正在运行的本机服务，适合 AI 在不直接碰数据文件的情况下控制 App。

终端命令名不要用容易冲突的通用名字。推荐格式：

```text
<namespace>-<app-id>
```

`namespace` 是用户、团队、厂商或作者自己的短命名；`app-id` 是稳定 App ID。命令名只使用字母、数字、点、下划线和短横线。备忘录示例使用 `demo-memo-openhouse`，真实用户可以用 `alice-memo-openhouse`、`jiwuyou-notes` 这类名字。不要直接占用 `notes`、`todo`、`agent` 这类未来市场中很容易冲突的全局命令名。

推荐 CLI 形态：

```bash
demo-memo-openhouse health
demo-memo-openhouse state
demo-memo-openhouse list
demo-memo-openhouse add "新的备忘录"
demo-memo-openhouse delete "<memo-id>"
demo-memo-openhouse --url http://127.0.0.1:23110 state
demo-memo-openhouse --url http://127.0.0.1:23110 add "通过 HTTP 添加备忘录"
```

CLI 输出默认应是 JSON。需要给人看的帮助信息只在 `help`、`--help` 或命令错误时输出。安装脚本应支持 `OPENHOUSE_CLI_NAMESPACE` 或类似环境变量，让用户或市场发布者指定自己的 namespace。

推荐 MCP 形态：

```bash
node /root/smallphoneai-repos/smallphone-home/apps/memo-openhouse/src/mcp-server.js
```

MCP server 使用 stdio transport，工具名稳定，例如：

```text
memo_openhouse_state
memo_openhouse_list_memos
memo_openhouse_add_memo
memo_openhouse_delete_memo
```

Web API、CLI 和 MCP 应共用同一个状态模块或业务模块。示例 App 使用 `src/state.js` 作为共享实现。

## App 服务定义

长期运行的 App 必须用 service-manager 管理，服务命令写在 `ServiceSpec`，不要写进组件 manifest。

最小服务定义结构：

```json
{
  "schemaVersion": 1,
  "id": "memo-openhouse",
  "service": {
    "name": "memo-openhouse",
    "description": "OpenHouse Memo custom web app example",
    "provider": "process",
    "command": ["node", "src/server.js"],
    "working_dir": "/root/smallphoneai-repos/smallphone-home/apps/memo-openhouse",
    "env": {
      "HOST": "127.0.0.1",
      "PORT": "23110",
      "OPENHOUSE_CUSTOM_APP_DATA_DIR": "/root/smallphoneai-repos/smallphone-home/apps/memo-openhouse/data"
    },
    "runtime": {},
    "restart": {
      "mode": "always",
      "max_retries": 0
    },
    "health": [
      {
        "type": "http",
        "url": "http://127.0.0.1:23110/health",
        "interval": "30s",
        "timeout": "5s"
      }
    ],
    "enabled": true,
    "tags": [
      "openhouseai",
      "smallphone",
      "group:local-stack",
      "openhouse-component:memo-openhouse",
      "smallphone-app:memo-openhouse"
    ]
  }
}
```

关键规则：

- `name` 和 `id` 使用稳定小写 ID。
- `provider: "process"` 管理本机前台长进程。
- `command` 是 argv 数组，不是随意拼接的 shell 字符串。
- 服务监听 `127.0.0.1`，除非用户明确要求 Tailscale 或局域网访问。
- `health` 必须验证真实页面或 API 可用。
- `tags` 用 `group:local-stack` 让运行栈可以统一控制。

## App 组件 manifest

组件 manifest 描述 UI 入口、桌面菜单、服务引用和 AI 可读说明。它必须包含四层对象：

```json
{
  "schemaVersion": 1,
  "id": "memo-openhouse",
  "title": "OpenHouse Memo",
  "description": "最小自定义备忘录 App 示例",
  "kind": "app",
  "shellMenu": {
    "visible": true,
    "section": "apps",
    "order": 120,
    "entry": {
      "type": "webview",
      "url": "http://127.0.0.1:23110/"
    },
    "controlEntry": {
      "type": "service-control",
      "serviceNames": ["memo-openhouse"],
      "serviceRefs": ["service-manager://services/memo-openhouse"]
    }
  },
  "smallphoneApp": {
    "visible": true,
    "section": "apps",
    "order": 120,
    "icon": "sparkles",
    "entry": {
      "type": "webview",
      "url": "http://127.0.0.1:23110/"
    },
    "controlEntry": {
      "type": "service-control",
      "serviceNames": ["memo-openhouse"],
      "serviceRefs": ["service-manager://services/memo-openhouse"]
    }
  },
  "serviceManager": {
    "required": true,
    "services": [
      {
        "name": "memo-openhouse",
        "title": "OpenHouse Memo",
        "role": "web",
        "port": 23110,
        "url": "http://127.0.0.1:23110/",
        "serviceRef": "service-manager://services/memo-openhouse",
        "health": {
          "type": "http",
          "url": "http://127.0.0.1:23110/health"
        },
        "controls": ["status", "start", "stop", "restart", "logs", "repair"],
        "repairActionRef": "service-manager://actions/memo-openhouse.repair"
      }
    ]
  },
  "ai": {
    "visible": true,
    "summaryDoc": "/root/.config/openhouseai/ai-docs/memo-openhouse/openhouse.ai.md",
    "capabilities": "/root/.config/openhouseai/ai-docs/memo-openhouse/capabilities.json",
    "intents": [
      { "name": "open", "target": "smallphoneApp.entry" },
      { "name": "control", "target": "smallphoneApp.controlEntry" }
    ]
  }
}
```

禁止在组件 manifest 中出现这些 key：

```text
command
shell
script
args
```

SmallPhone 读取 `smallphoneApp` 创建桌面 App；Android shell 读取 `shellMenu`；AI 通过 `/api/ai-capabilities` 读取 `ai` 和 `serviceManager`。

## 一次性注册

优先用 service-manager 的 registry apply API。它会校验组件、服务和 AI 文档，并同步到运行期注册目录。

```bash
SM_CONFIG="${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-$HOME/.config/openhouseai/service-manager/config.json}"
SM_ADDR="$(sed -n 's/.*"\(listen_addr\|listenAddr\|base_url\|baseUrl\|baseURL\|url\)"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\2/p' "$SM_CONFIG" 2>/dev/null | head -n 1)"
SM_URL="${SERVICE_MANAGER_URL:-${SM_ADDR:-http://127.0.0.1:20087}}"
TOKEN="$(service-manager token show --config "$SM_CONFIG" | head -n1)"
curl -fsS \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X POST \
  --data-binary @registry-apply.json \
  "${SM_URL%/}/api/v1/registry/apply"
```

payload 结构：

```json
{
  "components": [
    { "id": "memo-openhouse", "shellMenu": {}, "smallphoneApp": {}, "serviceManager": {}, "ai": {} }
  ],
  "services": [
    {
      "schemaVersion": 1,
      "id": "memo-openhouse",
      "service": {
        "name": "memo-openhouse",
        "provider": "process",
        "command": ["node", "src/server.js"]
      }
    }
  ],
  "aiDocs": [
    {
      "path": "memo-openhouse/openhouse.ai.md",
      "content": "# OpenHouse Memo\n\nAI-readable memo app summary.\n"
    }
  ]
}
```

手工文件路径也可以使用，但要记得同步或重启：

```text
$HOME/.config/openhouseai/components.d/<app-id>.json
$HOME/.config/openhouseai/service-manager/services.d/<app-id>.json
$HOME/.config/openhouseai/ai-docs/<app-id>/openhouse.ai.md
$HOME/.config/openhouseai/ai-docs/<app-id>/capabilities.json
```

## 自定义前端 shell

如果用户要改的是 SmallPhone 桌面壳，而不是新增 App，用 `examples/custom-phone-shell`。

自定义前端的代码可以和现有官方前端代码一致，而且这是推荐基线。正确做法不是直接改 `smallphone-active/generic-mini-phone-beta`，而是把它复制或 fork 到用户 shell 目录：

```text
$SMALLPHONE_HOME/shells/<shell-id>
```

这样系统仓库可以继续更新，用户自己的前端 shell 也能保留。

从现有 beta 前端复制安装为自定义 shell：

```bash
cd /root/openhouse/docs/examples/custom-phone-shell
bash install-from-existing-frontend.sh
```

默认源目录解析顺序：

```text
$SMALLPHONE_FRONTEND_SOURCE
$SMALLPHONE_ACTIVE/generic-mini-phone-beta
/root/smallphoneai-repos/smallphone-active/generic-mini-phone-beta
/root/projects/smallphone/smallphone-active/generic-mini-phone-beta
```

复制内容包括：

```text
index.html
style.css
scripts/
apps/
assets/    # 如果存在
```

复制完成后脚本会通过 smallphone-core 的 `/api/user-content` 注册并可选设为当前 active shell。

如果只是学习最小 shell，可以使用随文档提供的简化示例：

安装：

```bash
cd /root/openhouse/docs/examples/custom-phone-shell
bash install-shell.sh
```

安装位置：

```text
/root/smallphoneai-repos/smallphone-home/shells/openhouse-custom-shell
```

访问地址由 `smallphone-core/api` 快照条目决定：

```bash
CORE_URL="$(jq -er '.endpoints[] | select(.serviceId == "smallphone-core" and .name == "api") | .url' "$HOME/.config/openhouseai/runtime/endpoints.json")"
printf '%s\n' "${CORE_URL%/}/shells/openhouse-custom-shell/"
```

它通过 smallphone-core 公开 API 获取 App 列表：

```text
GET /api/app-registry
GET /api/components
GET /api/ai-capabilities
GET /api/service-manager/services
GET /api/service-manager/services/:id/status
POST /api/service-manager/services/:id/start
POST /api/service-manager/services/:id/stop
POST /api/service-manager/services/:id/restart
POST /api/service-manager/services/:id/repair
```

前端 shell 只处理公开数据。不要在 shell 里保存 service-manager token、模型 key、cc-connect token 或 provider secret。

## AI 更新流程

用户第一次拿到的 App 不一定是 Git 仓库。为了让用户方便更新，AI 应把“更新”做成一次可解释、可回滚的任务，而不是要求用户手工 git 操作。

标准步骤：

1. 读取 `/api/components`、`/api/app-registry` 或 `~/.config/openhouseai/components.d/<id>.json`，找到 App ID、服务 ID、安装目录和当前端口。
2. 判断安装目录是否是 Git 仓库：有 `.git` 就用 `git fetch`、`git status`、`git diff`；没有 `.git` 就把它当作普通 bundle。
3. 更新代码时保留 `data/`、`uploads/`、SQLite、`.env.local`、用户配置和 `SMALLPHONE_HOME`。
4. 更新或重写 `ServiceSpec`、component manifest、AI 文档，再调用 `/api/v1/registry/apply`。
5. 调用 service-manager `restart`，再检查 `/health`、`/api/service-manager/services/:id/status`、`/api/app-registry` 和 `/api/ai-capabilities`。
6. 把改动摘要、服务状态和失败恢复方式告诉用户。

如果手机 Ubuntu 需要代理才能访问 GitHub，AI 可以临时使用用户配置的代理。不要把代理写死进 App：

```bash
export http_proxy="http://100.75.71.23:7897"
export https_proxy="http://100.75.71.23:7897"
git -C "$APP_DIR" fetch --all --tags
```

如果不是 Git 仓库，推荐 AI 采用这个 bundle 更新策略：

```text
$APP_DIR/releases/<timestamp>/     # 新版本解包
$APP_DIR/current -> releases/...   # 可选软链接
$APP_DIR/data/                     # 持久化数据，不随版本覆盖
```

然后把 service-manager 的 `working_dir` 指向当前版本目录，或保持固定目录并只覆盖代码文件。不要删除用户数据。

## 调试清单

检查 service-manager：

```bash
SM_CONFIG="${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-$HOME/.config/openhouseai/service-manager/config.json}"
SM_ADDR="$(sed -n 's/.*"\(listen_addr\|listenAddr\|base_url\|baseUrl\|baseURL\|url\)"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\2/p' "$SM_CONFIG" 2>/dev/null | head -n 1)"
SM_URL="${SERVICE_MANAGER_URL:-${SM_ADDR:-http://127.0.0.1:20087}}"
case "$SM_URL" in
  http://0.0.0.0*) SM_URL="http://127.0.0.1${SM_URL#http://0.0.0.0}" ;;
  https://0.0.0.0*) SM_URL="https://127.0.0.1${SM_URL#https://0.0.0.0}" ;;
  :*) SM_URL="http://127.0.0.1$SM_URL" ;;
  0.0.0.0:*) SM_URL="http://127.0.0.1:${SM_URL#0.0.0.0:}" ;;
esac
curl -fsS "${SM_URL%/}/api/v1/health"
TOKEN="$(service-manager token show --config "$SM_CONFIG" | head -n1)"
curl -fsS -H "Authorization: Bearer $TOKEN" \
  "${SM_URL%/}/api/v1/services/memo-openhouse/status"
```

检查 SmallPhone Core：

```bash
CORE_URL="$(jq -er '.endpoints[] | select(.serviceId == "smallphone-core" and .name == "api") | .url' "$HOME/.config/openhouseai/runtime/endpoints.json")"
curl -fsS "${CORE_URL%/}/api/app-registry"
curl -fsS "${CORE_URL%/}/api/components"
curl -fsS "${CORE_URL%/}/api/ai-capabilities"
```

检查 App：

```bash
curl -fsS http://127.0.0.1:23110/health
curl -fsS http://127.0.0.1:23110/api/state
```

如果桌面没出现入口：

- 确认 component manifest 的 `id` 和文件名一致。
- 确认 `smallphoneApp.entry.type` 是 `webview` 且有 `url`。
- 确认 manifest 没有 `command`、`shell`、`script`、`args`。
- 确认服务 ID 与 `serviceManager.services[*].name`、`controlEntry.serviceNames` 一致。
- 回到桌面或刷新 SmallPhone 前端，让它重新读取 registry。

## 红线

- 不用 `nohup`、后台 shell 或终端会话长期运行 App。
- 不把命令写进 component manifest。
- 不把 token/key 写入前端、URL、localStorage、manifest、AI 文档或可分享日志。
- 不把用户 App 数据写进 `smallphone-active` 系统仓库。
- 不要求用户理解 Git；更新应由 AI 读取文档、检查状态、执行并汇报。
