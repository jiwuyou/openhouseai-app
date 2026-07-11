# service-manager 操作手册

service-manager 是安装完成后的运行期控制平面。AI agent 管理后台服务时，默认通过 service-manager，而不是直接后台启动进程。

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
- `smallphone`
- `pi-agent`
- `pi-web`
- `cloudcli`，即 `cc/codex` 入口后端，当前目标端口是 `23083`
- `cc-connect` / `openhouse-connect`

未来可继续注册：

- MCP server
- 插件服务
- 用户自定义后台服务

具体服务 ID 以 `service-manager list` 或 `/api/v1/services` 返回结果为准。

## 注册服务到 service-manager

service-manager 自身由首次安装链路或 bootstrap 安装、启动。普通后台服务不要自己长期 `nohup` 或从 Android UI 直接拉起，而是注册为 service-manager 的 `ServiceSpec`。

OpenHouseAI 默认读取这个注册目录：

```text
$HOME/.config/openhouseai/service-manager/services.d/*.json
```

这个目录也可以通过 service-manager 配置里的 `service_registry_dir` 覆盖。`services.d/*.json` 会在 `service-manager serve` 启动时加载，并按稳定服务名 upsert 到服务列表。

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
- `command` 是结构化 argv 数组，不是 shell 字符串。
- 被管理命令必须是前台长进程。脚本型服务推荐让 `sh -lc` 作为 supervisor，显式 `wait` 子进程并在 TERM/INT/HUP 时转发停止信号。
- 不要把脚本型服务注册成 `["openhouse-pi-web-start"]` 或 `["/bin/sh", "/data/data/com.termux/files/home/.local/bin/openhouse-pi-web-start"]`。如果直接跟踪会改写进程标题的 Node/Next 主进程，运行控制容易出现 stale pidfile 或 cmdline mismatch。
- `PATH` 必须包含 Termux npm prefix、wrapper 所在目录和 Termux prefix。pi-web 默认需要 `/data/data/com.termux/files/home/.npm-global/bin`、`/data/data/com.termux/files/home/.local/bin` 和 `/data/data/com.termux/files/usr/bin`。
- `tags` 里用 `group:<name>` 表示服务分组，例如 `group:local-stack`。

写入 `services.d` 文件后，需要让 service-manager 重新加载注册目录。service-manager 只在启动时加载 `services.d/*.json`，因此默认做法是回到 bootstrap 重新启动控制平面：

```bash
cd "$HOME/.smallphoneai-bootstrap"
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
service-manager list
service-manager status pi-web
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
service-manager list
service-manager status pi-web
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

兼容读取路径：

```text
$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu/root/.config/service-manager/config.json
$HOME/.config/service-manager/config.json
```

运行日志：

```text
$HOME/.smallphoneai/logs/service-manager.log
```

## 状态检查

优先使用 bootstrap 状态，因为它会同时检查 service-manager、pi-web、pi-agent、cc-connect 和端口：

```bash
cd "$HOME/.smallphoneai-bootstrap"
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

查看服务列表：

```bash
service-manager list
```

查看某个服务状态：

```bash
service-manager status pi-web
```

如果本地 CLI 不可用，使用 API。

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

## 启动和修复控制平面

如果 service-manager 不可访问，先使用 bootstrap 启动：

```bash
cd "$HOME/.smallphoneai-bootstrap"
bash bootstrap.sh start
```

如果启动失败，再修复运行栈：

```bash
cd "$HOME/.smallphoneai-bootstrap"
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
- 不要修改旧路径 `$HOME/.config/service-manager/config.json` 作为首选配置；优先使用 OpenHouse 专用路径。
- 不要在不知道服务 ID 的情况下猜测 stop/restart。
