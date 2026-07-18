# service-manager 操作手册

service-manager 是安装完成后的运行期控制平面。AI agent 管理后台服务时，默认通过 service-manager，而不是直接后台启动进程。本文以 `service-manager 0.3.2` 的正式契约为基线。

OpenHouseAI 的正式部署基线是：service-manager daemon 运行在 Termux native 层，使用 OpenHouse 专用配置和 token。Ubuntu/proot 里的长期服务由 Termux native service-manager 通过 provider 管理，不在 Ubuntu 内另起一个常驻 service-manager。

`service-manager` CLI 只用于 `serve`、`doctor`、token 和系统服务安装等 daemon 管理。它没有 `list`、`status`、`register`、`create` 或 `upsert` 子命令；业务服务的查询、注册和生命周期控制统一使用带 bearer token 的 REST API。用户能力的组合检查使用 `openhouse-system check <subject-id>`，详见 `OPENHOUSE_SYSTEM.md`。

## 角色

service-manager 负责：

- 服务列表。
- 服务状态。
- 服务启动、停止、重启、修复。
- 服务日志。
- 本地运行栈 `group:local-stack`。
- OpenHouseAI 组件注册同步后的服务入口。

当前核心服务通常包括：

- `service-manager`
- `openhouse-web`
- `smallphone`
- `pi-agent`
- `pi-web`
- `cloudcli`，即 `cc/codex` 入口后端，当前目标端口是 `23083`
- `cc-connect` / `openhouse-connect`

未来可继续注册：

- MCP server
- 插件服务
- 用户自定义后台服务

具体服务 ID 以 `GET /api/v1/services` 返回结果为准，不要猜测。

## 注册服务到 service-manager

service-manager 自身由首次安装链路或 bootstrap 安装、启动。普通后台服务不要自己长期 `nohup` 或从 Android UI 直接拉起，而是注册为 service-manager 的 `ServiceSpec`。

OpenHouseAI 默认读取这个注册目录：

```text
$HOME/.config/openhouseai/service-manager/services.d/*.json
```

这个目录也可以通过 service-manager 配置里的 `service_registry_dir` 覆盖。`services.d/*.json` 会在 OpenHouse canonical daemon 启动时加载，并按稳定服务名 upsert 到服务列表。

一个使用 APK 内置 standalone runtime 的 pi-web 服务示例：

```json
{
  "name": "pi-web",
  "description": "pi-agent 本地页面运行时",
  "provider": "termux-process",
  "command": [
    "sh",
    "-lc",
    "openhouse-pi-web-start & child=$!; trap 'kill -TERM $child 2>/dev/null; wait $child 2>/dev/null || true' TERM INT HUP; wait $child"
  ],
  "working_dir": "/data/data/com.termux/files/home/.local/share/openhouseai/pi-web",
  "env": {
    "HOME": "/data/data/com.termux/files/home",
    "OPENHOUSE_PI_WEB_RUNTIME_DIR": "/data/data/com.termux/files/home/.local/share/openhouseai/pi-web",
    "HOSTNAME": "127.0.0.1",
    "PI_WEB_HOST": "127.0.0.1",
    "PORT": "30141",
    "PI_WEB_PORT": "30141",
    "PI_CODING_AGENT_DIR": "/data/data/com.termux/files/home/.pi",
    "PATH": "/data/data/com.termux/files/home/.npm-global/bin:/data/data/com.termux/files/home/.local/bin:/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin"
  },
  "runtime": {
    "strategy": "termux-process",
    "runtime": "termux",
    "platform": "android-arm64"
  },
  "restart": {
    "mode": "always",
    "max_retries": 0
  },
  "health": [
    {
      "type": "http",
      "url": "http://127.0.0.1:30141/",
      "interval": "30s",
      "timeout": "5s"
    }
  ],
  "enabled": true,
  "tags": ["openhouseai", "pi", "ui", "group:local-stack"]
}
```

字段原则：

- `name` 是服务 ID，只使用字母、数字、`.`、`_`、`-`。
- `provider: "termux-process"` 表示 service-manager 在 Termux 原生层启动长期前台服务；需要进入 Ubuntu/proot 的服务才使用 `proot-distro` provider。
- `provider: "proot-distro"` 表示 service-manager 仍在 Termux native 层运行，但启动命令进入 Ubuntu/proot。服务如果属于非 root Linux 用户，应在 `runtime.user` 中声明该用户。
- `command` 是结构化 argv 数组，不是 shell 字符串。
- 被管理命令必须是前台长进程。脚本型服务推荐让 `sh -lc` 作为 supervisor，显式 `wait` 子进程并在 TERM/INT/HUP 时转发停止信号。
- 不要把脚本型服务注册成 `["openhouse-pi-web-start"]` 或 `["/bin/sh", "/data/data/com.termux/files/home/.local/bin/openhouse-pi-web-start"]`。如果直接跟踪会改写进程标题的 Node/Next 主进程，运行控制容易出现 stale pidfile 或 cmdline mismatch。
- `PATH` 必须包含 Termux npm prefix、wrapper 所在目录和 Termux prefix。pi-web 默认需要 `/data/data/com.termux/files/home/.npm-global/bin`、`/data/data/com.termux/files/home/.local/bin` 和 `/data/data/com.termux/files/usr/bin`。
- `tags` 里用 `group:<name>` 表示服务分组，例如 `group:local-stack`。

写入 `services.d` 文件后，需要让 service-manager 重新加载注册目录。service-manager 只在启动时加载 `services.d/*.json`，因此默认做法是回到 bootstrap 重新启动控制平面：

```bash
resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1)
[ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }
cd "$resource_dir/bootstrap"
bash bootstrap.sh start
```

如果不希望重启控制平面，也可以直接通过 API 注册或更新服务：

```bash
curl -q -fsS --max-time 10 \
  -X POST \
  -K /tmp/openhouse-sm-curl.cfg \
  -H "Content-Type: application/json" \
  -d @/path/to/my-agent.json \
  "$SM_URL/api/v1/services"
```

注册后验证：

```bash
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services"
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/pi-web/status"
```

## 注册到桌面、菜单总览 App 和侧边栏

OpenHouseAI 桌面、菜单总览 App 和侧边栏读取组件注册目录：

```text
$HOME/.config/openhouseai/components.d/*.json
```

组件注册只描述入口、标题、分区、桌面展示和 service-manager 绑定关系。不要在组件注册里写 `command`、`shell`、`script` 或 `args`；这些执行细节必须放在 service-manager 的 `ServiceSpec` 中。

一个同时提供 `pi-agent` 进入入口、桌面入口和控制入口的组件示例：

```json
{
  "id": "pi-agent",
  "enabled": true,
  "shellMenu": {
    "title": "pi-agent",
    "subtitle": "首次配置助手",
    "section": "ai",
    "order": 80,
    "visible": true,
    "favorite": true,
    "desktop": {
      "visible": true,
      "order": 80,
      "icon": "agent"
    },
    "entry": {
      "type": "webview",
      "url": "http://127.0.0.1:30141/"
    },
    "controlEntry": {
      "type": "service-control",
      "title": "控制",
      "serviceRefs": [
        "service-manager://services/pi-agent",
        "service-manager://services/pi-web"
      ]
    }
  },
  "smallphoneApp": {},
  "serviceManager": {
    "services": [
      {
        "name": "pi-agent",
        "serviceRef": "service-manager://services/pi-agent"
      },
      {
        "name": "pi-web",
        "serviceRef": "service-manager://services/pi-web"
      }
    ]
  },
  "ai": {}
}
```

`pi-agent` 是桌面/侧边栏一级入口，和 `SmallPhone`、`cc/codex` 同级。pi-web 是该入口背后的本地页面运行时，不应作为新手教学里的另一个大入口。`pi-agent` 负责首次配置、文档索引和配置迁移，不是唯一主工作台。

`cc/codex` 是统一入口，服务控制可以绑定 `cloudcli`、`cc-connect`、Codex 相关服务或后续 Claude Code 服务。除非产品菜单策略变化，不要把 CloudCLI、Claude Code、Codex 拆成多个一级入口。

标准组件清单使用四层结构：`shellMenu`、`smallphoneApp`、`serviceManager`、`ai`。即使某一层暂时不用，也保留为空对象，方便通过 registry API 校验和同步。

桌面和侧边栏行为：

- `entry.type: "webview"` 会在 OpenHouseAI 内打开本地 Web 页面。
- `controlEntry.type: "service-control"` 会显示服务控制入口。
- 同时有 `entry` 和 `controlEntry` 时，侧边栏会显示打开按钮和控制按钮。
- 只有 `controlEntry`、没有 `entry` 时，会显示控制型入口。
- `favorite: true` 或 `home: true` 会让入口进入更靠前的快捷区域。
- `desktop.visible: true` 会让入口进入原生桌面页；桌面图标默认只显示图标和名称，稀疏槽位布局、改名和图标 override 由桌面层保存。用户长按进入编辑模式，可跨屏拖动、拖到末尾新建屏、隐藏、重置或设为默认入口；状态通过 App 详情、打开失败面板或 App 页右侧控制栏查看。

`serviceRefs` 支持：

```text
service-manager://services/<serviceId>
service-manager://actions/<serviceId>.start
service-manager://actions/<serviceId>.stop
service-manager://actions/<serviceId>.restart
service-manager://actions/<serviceId>.repair
```

桌面、菜单总览 App 或侧边栏在进入或回到页面时会重新读取 `components.d`。如果新入口没有出现，先检查：

```bash
ls -la "$HOME/.config/openhouseai/components.d"
openhouse-system validate
openhouse-system check pi-agent
```

再回到 OpenHouseAI 桌面/主菜单，或重新打开页面触发刷新。

## 默认地址和配置

service-manager endpoint 支持更改。调用方必须先读取配置或环境变量，最后才使用默认 fallback。

解析顺序：

1. OpenHouse 专用配置里的 `base_url` 或 `listen_addr`。
2. `SERVICE_MANAGER_URL`。
3. `SMALLPHONEAI_SERVICE_MANAGER_BIND`。
4. 默认 fallback。

默认 fallback：

```text
http://127.0.0.1:20087
```

`20087` 不是不可变端口。如果配置里监听 `0.0.0.0` 或通配地址，本机和 Android 侧访问时应转换为 `127.0.0.1`。

优先配置文件：

```text
$HOME/.config/openhouseai/service-manager/config.json
```

这是 OpenHouse 唯一允许用于 daemon 与 token 的配置。直接启动时必须显式传入同一份配置和其中的
`listen_addr`：

```bash
SM_CONFIG="$HOME/.config/openhouseai/service-manager/config.json"
SM_BIND="$(sed -n 's/.*"listen_addr"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$SM_CONFIG" | head -n 1)"
[ -n "$SM_BIND" ] || { echo "OpenHouse canonical config 缺少 listen_addr" >&2; exit 1; }
service-manager serve --config "$SM_CONFIG" --bind "$SM_BIND"
```

读取 token 也必须显式使用同一份配置：

```bash
service-manager token show --config "$SM_CONFIG"
```

在 OpenHouse 环境中禁止裸运行 `service-manager serve` 或 `service-manager token show`。裸命令会读取
`$HOME/.config/service-manager/config.json`，从而产生第二份配置、第二个 token，最终表现为 API 可达但
Android、bootstrap 和 AI 使用 canonical token 时持续收到 `401`。日常启动优先使用 APK bootstrap 或
“启动运行中枢”固定脚本；上面的命令只用于明确的人工诊断和恢复。

运行日志：

```text
$HOME/.smallphoneai/logs/service-manager.log
```

## 运行时 endpoint 快照

service-manager 是运行地址的唯一发布者。它会把本轮已经通过 health 与进程/端口 ownership
检查的入口原子写入：

```text
$HOME/.config/openhouseai/runtime/endpoints.json
```

Android、OpenHouse Web、SmallPhone 等消费者使用稳定的 `serviceId + endpointName` 查询该文件，
不得读取内部 `ports.json`、猜测 preferred 端口或在快照缺失时自动回退 endpoint REST。
快照 `state` 必须是 `ready` 且 `expiresAt` 尚未到期；条目缺失就表示当前入口不可消费。
启动、停止、常驻、日志和详细失败原因仍按需使用 REST API。

```bash
ENDPOINTS_FILE="${OPENHOUSE_ENDPOINTS_FILE:-$HOME/.config/openhouseai/runtime/endpoints.json}"
jq -e '.schemaVersion == 1 and .state == "ready"' "$ENDPOINTS_FILE"
jq -r '.endpoints[] | [.serviceId, .name, .url] | @tsv' "$ENDPOINTS_FILE"
```

快照每 30 秒集中重验证，TTL 为 120 秒。service-manager 启动时先发布空的
`initializing` 快照，完成重验证后才切换为 `ready`；停止或重新分配端口时会立即撤销旧条目。

## 状态检查

优先使用 bootstrap 状态，因为它会同时检查 service-manager、pi-web、pi-agent、cc-connect 和端口：

```bash
resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1)
[ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }
cd "$resource_dir/bootstrap"
bash bootstrap.sh status
```

只检查 service-manager health：

```bash
SM_CONFIG="$HOME/.config/openhouseai/service-manager/config.json"
SM_ADDR="$(sed -n 's/.*"\(listen_addr\|listenAddr\|base_url\|baseUrl\|url\)"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\2/p' "$SM_CONFIG" | head -n 1)"
case "$SM_ADDR" in
  http://0.0.0.0*) SM_URL="http://127.0.0.1${SM_ADDR#http://0.0.0.0}" ;;
  http://*|https://*) SM_URL="$SM_ADDR" ;;
  :*) SM_URL="http://127.0.0.1$SM_ADDR" ;;
  0.0.0.0:*) SM_URL="http://127.0.0.1:${SM_ADDR#0.0.0.0:}" ;;
  *) SM_URL="http://$SM_ADDR" ;;
esac
curl -fsS --max-time 2 "${SM_URL%/}/api/v1/health"
```

## 版本和协议定位

APK 会把完整版本化资源放在 `update-resources/apk-*`，并在需要 AI 处理时写入待处理标记。查看最新 payload 摘要：

```bash
resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1)
[ -n "$resource_dir" ] && [ -f "$resource_dir/product-payloads/manifest.json" ] || { echo "未找到可用的 APK payload manifest" >&2; exit 1; }
cat "$HOME/.local/share/openhouseai/update-resources/PENDING_APK_RESOURCES.json" 2>/dev/null || true
sed -n '1,220p' "$resource_dir/product-payloads/manifest.json"
```

摘要至少应包含 APK `versionName/versionCode`、Termux package variant、bootstrap asset tree sha256、`service-manager`、registryApi、`pi-agent`、`pi-web` 和 `aionui-web`。如果 registryApi 仍显示 `unknown`，说明当前 payload manifest 没有声明协议版本，安装脚本必须保留兼容兜底。

构建 APK 前会执行 `scripts/validate-openhouse-payloads.sh`。这个校验会拒绝 sha/size 不一致的 payload，也会拒绝可能把 `pi-web.json` 截断成 0 字节的注册脚本。

查看服务列表和单个服务状态时，使用下文“API 调用模板”准备的认证配置：

```bash
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services"
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/pi-web/status"
```

## 运行控制页展示规则

Android 运行控制页应把 service-manager 显示为“控制中枢”。它不是普通业务服务，而是安装完成后的运行期控制平面。

建议状态映射：

| 控制中枢状态 | 对用户说明 | 推荐动作 |
| --- | --- | --- |
| 运行中 | 可以管理 `pi-agent`、`cc/codex` 和其它后台服务。 | 无需修复。 |
| 未运行 | 上层服务可能打不开，但数据通常还在。 | 点击修复或启动运行栈。 |
| 异常 | 控制平面启动失败或配置/token 不一致。 | 点击修复，完成后刷新页面。 |
| 无法连接 | App 暂时无法访问本地 API。 | 先修复控制中枢，再看具体服务。 |

修复说明应按“状态 -> 影响 -> 推荐动作 -> 修复过程 -> 结果”呈现。默认不要把长日志直接展示给普通用户；提供“查看详细日志”和“复制诊断信息”即可。

服务启动、关闭、重启或修复后，页面必须提示用户刷新，因为 WebView 内部状态和 service-manager 状态可能不会同步重载。

所有服务控制页都应提供“返回菜单”按钮，避免用户被困在单个服务控制页面。

## API 调用模板

读取 token：

```bash
SM_CONFIG="$HOME/.config/openhouseai/service-manager/config.json"
SM_TOKEN="$(sed -n 's/.*"auth_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$SM_CONFIG" | head -n 1)"
SM_ADDR="$(sed -n 's/.*"\(listen_addr\|listenAddr\|base_url\|baseUrl\|url\)"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\2/p' "$SM_CONFIG" | head -n 1)"
case "$SM_ADDR" in
  http://0.0.0.0*) SM_URL="http://127.0.0.1${SM_ADDR#http://0.0.0.0}" ;;
  https://0.0.0.0*) SM_URL="https://127.0.0.1${SM_ADDR#https://0.0.0.0}" ;;
  http://*|https://*) SM_URL="$SM_ADDR" ;;
  :*) SM_URL="http://127.0.0.1$SM_ADDR" ;;
  0.0.0.0:*) SM_URL="http://127.0.0.1:${SM_ADDR#0.0.0.0:}" ;;
  *) SM_URL="http://$SM_ADDR" ;;
esac
SM_URL="${SM_URL%/}"
```

创建 curl 配置：

```bash
printf 'header = "Authorization: Bearer %s"\n' "$SM_TOKEN" > /tmp/openhouse-sm-curl.cfg
```

列出服务：

```bash
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services"
```

查看服务状态：

```bash
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/pi-web/status"
```

查看服务日志：

```bash
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/pi-web/logs?limit=120"
```

启动、停止、重启或修复服务：

```bash
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/pi-web/start"
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/pi-web/stop"
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/pi-web/restart"
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/pi-web/repair"
```

启动本地运行栈：

```bash
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/groups/local-stack/start"
```

批量状态接口返回 wrapper 数组，每项形如 `{ "service": {...}, "status": {...}, "error": "" }`。读取运行状态时使用 `.status.state`；不要把 `.serviceId` 或 `.state` 当成 wrapper 顶层字段：

```bash
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg \
  "$SM_URL/api/v1/services/statuses" \
  | jq '[.[] | {id: .service.id, state: (.status.state // "unknown"), error}]'
```

单服务 `GET /api/v1/services/:id/status` 直接返回状态对象，运行状态字段是顶层 `.state`。

## 常驻设置与取消

`service-manager 0.3.2` 把常驻意图保存在独立策略中：

```text
<data_dir>/residency.json
```

查询全部或单服务策略：

```bash
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/residency"
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/pi-web/residency"
```

设置和取消常驻：

```bash
curl -q -fsS --max-time 10 -X PUT -K /tmp/openhouse-sm-curl.cfg \
  -H 'Content-Type: application/json' -d '{"resident":true}' \
  "$SM_URL/api/v1/services/pi-web/residency"

curl -q -fsS --max-time 10 -X PUT -K /tmp/openhouse-sm-curl.cfg \
  -H 'Content-Type: application/json' -d '{"resident":false}' \
  "$SM_URL/api/v1/services/pi-web/residency"
```

返回字段包括 `serviceId`、`resident`、`suspendedByUser`、`lastError`、`updatedAt` 和 `registered`。

语义必须区分：

- `resident: false` 只取消以后自动拉起，不会停止当前正在运行的服务；若还要停服务，再调用 `/stop`。
- 手动停止一个常驻服务会保留常驻意图，但设置 `suspendedByUser: true`，避免立刻反弹。
- 手动启动或重启会清除暂停，常驻对账重新生效。
- service-manager 启动后会对账常驻且未暂停的服务，运行期间也会周期性复查。
- ServiceSpec 的 `residentByDefault` 只用于首次建立策略，不能覆盖用户之后的选择。
- `DELETE /api/v1/residency/:id` 只用于清理服务已经删除后的孤立策略；已注册服务不能用它代替取消常驻。

清理孤立策略：

```bash
curl -q -fsS --max-time 10 -X DELETE -K /tmp/openhouse-sm-curl.cfg \
  "$SM_URL/api/v1/residency/removed-service-id"
```

## 正确安装 OpenHouse Web

OpenHouse Web 是独立的本地桌面与服务控制服务，不负责聊天。正式运行基线：

```text
服务 ID：openhouse-web
入口名：web
首选端口：22110
安装目录：$HOME/.local/lib/openhouse-web
数据目录：$HOME/.local/share/openhouseai/openhouse-web
启动器：$PREFIX/bin/openhouse-web
service-manager：http://127.0.0.1:20087
```

首次安装必须消费 APK 中同版本、已校验的 `openhouse-web.tar`，然后依次执行 payload 自带的 `scripts/install.sh`、`scripts/check.sh` 和 `scripts/register-service.sh`。注册脚本通过 `/api/v1/registry/apply` 同时写入服务和 component；不要只启动 Node 进程，也不要长期使用散落的 `nohup`。

从最新 APK 资源目录单独重装或修复该组件：

```bash
resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1)
[ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/scripts/50-install-runtime-components.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }
SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="$resource_dir/product-payloads" \
SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="$resource_dir/product-payloads" \
SMALLPHONEAI_COMPONENT_SOURCE_MODE=bundle \
SMALLPHONEAI_COMPONENT_TARGETS=openhouse-web \
bash "$resource_dir/bootstrap/scripts/50-install-runtime-components.sh"
```

安装后验证：

```bash
test -f "$HOME/.local/lib/openhouse-web/src/server.mjs"
test -x "$PREFIX/bin/openhouse-web"
OPENHOUSE_WEB_URL="$(jq -er '.endpoints[] | select(.serviceId == "openhouse-web" and .name == "web") | .url' "$HOME/.config/openhouseai/runtime/endpoints.json")"
curl -fsS --max-time 5 "${OPENHOUSE_WEB_URL%/}/health"
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/openhouse-web/status"
openhouse-system check service-control
```

正式 ServiceSpec 使用 Termux native Node、固定服务 ID `openhouse-web`、首选端口 `22110`、HTTP health 和 `residentByDefault: true`。消费者通过 `openhouse-web + web` 解析运行地址；component 的 `controlEntry.serviceRefs` 必须包含 `service-manager://services/openhouse-web`。

首次启动会在数据目录以明文创建默认密码 `123456`；文件权限应为 `0600`，用户可在已登录页面修改。Android 通过一次性 bootstrap ticket 建立 Web 会话，浏览器不应获得 service-manager bearer token。

## 启动和修复控制平面

如果 service-manager 不可访问，先使用 bootstrap 启动：

```bash
resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1)
[ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }
cd "$resource_dir/bootstrap"
bash bootstrap.sh start
```

如果启动失败，再修复运行栈：

```bash
resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1)
[ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }
cd "$resource_dir/bootstrap"
bash bootstrap.sh repair
```

不要直接绕过 service-manager 启动 pi、pi-web、CloudCLI、cc-connect、MCP server 或 agent server 的长期进程。

## 显式关闭

用户要求关闭后台时，必须通过 service-manager 停止相关服务。

推荐顺序：

1. 列出服务，确认服务 ID。
2. 停止上层入口，例如 pi-web、CloudCLI、pi-agent、MCP 服务。
3. 停止桥接服务，例如 cc-connect。
4. 保留或停止 service-manager 取决于用户要求：
   - 用户只要求关闭 OpenHouse AI 服务：可以保留 service-manager，方便再次启动。
   - 用户要求“一点不占 CPU 和内存”：在确认没有服务需要托管后，也应停止 service-manager 本身或通过 App 侧关闭运行栈。
5. 再次检查状态和残留进程。

停止动作示例：

```bash
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/cloudcli/stop"
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/pi-web/stop"
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/pi-agent/stop"
```

如果某个服务 ID 不存在，不要猜测；先重新读取服务列表。

## 日志处理

读日志优先使用 service-manager API：

```bash
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/cloudcli/logs?limit=160"
```

service-manager 自身日志：

```bash
tail -n 160 "$HOME/.smallphoneai/logs/service-manager.log"
```

向用户汇报时只摘取关键错误行，不要输出 token、API key 或大量完整日志。

## 禁止事项

- 不要绕过 service-manager 长期启动后台服务。
- 不要在没有用户确认时停止全部服务。
- 不要把 token 打印到聊天或日志。
- OpenHouse 中禁止把旧路径 `$HOME/.config/service-manager/config.json` 用于 daemon 或 token；发现它时只识别并报告错误实例，不把它当作可用配置。
- 不要在不知道服务 ID 的情况下猜测 stop/restart。
