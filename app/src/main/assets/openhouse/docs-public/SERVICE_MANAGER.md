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
- `smallphone-core`
- `smallphone-frontend-beta`
- `cloudcli`
- `cc-connect` / `openhouse-connect`

未来可继续注册：

- `openhouse-agent`
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

一个运行在 Termux Ubuntu 内的服务示例：

```json
{
  "name": "my-agent",
  "description": "本机 agent 服务",
  "provider": "proot-distro",
  "command": ["node", "server.js"],
  "working_dir": "/root/my-agent",
  "env": {
    "PORT": "23100"
  },
  "runtime": {
    "distro": "ubuntu"
  },
  "restart": {
    "mode": "always",
    "max_retries": 0
  },
  "health": [
    {
      "type": "http",
      "url": "http://127.0.0.1:23100/health",
      "interval": "30s",
      "timeout": "5s"
    }
  ],
  "enabled": true,
  "tags": ["openhouseai", "agent", "group:local-stack"]
}
```

字段原则：

- `name` 是服务 ID，只使用字母、数字、`.`、`_`、`-`。
- `provider: "proot-distro"` 表示命令实际在 Ubuntu proot 中运行。
- `command` 是结构化 argv 数组，不是 shell 字符串。
- 被管理命令必须是前台长进程；如果使用包装脚本，脚本最后要 `exec` 到真实服务。
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
service-manager status my-agent
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/my-agent/status"
```

## 注册到主菜单和侧边栏

OpenHouseAI 主菜单/侧边栏读取组件注册目录：

```text
$HOME/.config/openhouseai/components.d/*.json
```

组件注册只描述入口、标题、分区和 service-manager 绑定关系。不要在组件注册里写 `command`、`shell`、`script` 或 `args`；这些执行细节必须放在 service-manager 的 `ServiceSpec` 中。

一个同时提供打开入口和控制入口的组件示例：

```json
{
  "id": "my-agent",
  "enabled": true,
  "shellMenu": {
    "title": "我的 Agent",
    "subtitle": "本机 agent 工作台",
    "section": "ai",
    "order": 80,
    "visible": true,
    "favorite": true,
    "entry": {
      "type": "webview",
      "url": "http://127.0.0.1:23100/"
    },
    "controlEntry": {
      "type": "service-control",
      "title": "控制",
      "serviceRefs": ["service-manager://services/my-agent"]
    }
  },
  "smallphoneApp": {},
  "serviceManager": {
    "services": [
      {
        "name": "my-agent",
        "serviceRef": "service-manager://services/my-agent"
      }
    ]
  },
  "ai": {}
}
```

标准组件清单使用四层结构：`shellMenu`、`smallphoneApp`、`serviceManager`、`ai`。即使某一层暂时不用，也保留为空对象，方便通过 registry API 校验和同步。

侧边栏行为：

- `entry.type: "webview"` 会在 OpenHouseAI 内打开本地 Web 页面。
- `controlEntry.type: "service-control"` 会显示服务控制入口。
- 同时有 `entry` 和 `controlEntry` 时，侧边栏会显示打开按钮和控制按钮。
- 只有 `controlEntry`、没有 `entry` 时，会显示控制型入口。
- `favorite: true` 或 `home: true` 会让入口进入更靠前的快捷区域。

`serviceRefs` 支持：

```text
service-manager://services/<serviceId>
service-manager://actions/<serviceId>.start
service-manager://actions/<serviceId>.stop
service-manager://actions/<serviceId>.restart
service-manager://actions/<serviceId>.repair
```

主菜单在进入或回到页面时会重新读取 `components.d`。如果新入口没有出现，先检查：

```bash
ls -la "$HOME/.config/openhouseai/components.d"
service-manager list
service-manager status my-agent
```

再回到 OpenHouseAI 主菜单，或重新打开主菜单页面触发刷新。

## 默认地址和配置

默认 API：

```text
http://127.0.0.1:20087
```

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

优先使用 bootstrap 状态，因为它会同时检查 service-manager、SmallPhone、cc-connect 和端口：

```bash
cd "$HOME/.smallphoneai-bootstrap"
bash bootstrap.sh status
```

只检查 service-manager health：

```bash
curl -fsS --max-time 2 http://127.0.0.1:20087/api/v1/health
```

查看服务列表：

```bash
service-manager list
```

查看某个服务状态：

```bash
service-manager status smallphone-core
```

如果本地 CLI 不可用，使用 API。

## API 调用模板

读取 token：

```bash
SM_CONFIG="$HOME/.config/openhouseai/service-manager/config.json"
SM_TOKEN="$(sed -n 's/.*"auth_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$SM_CONFIG" | head -n 1)"
SM_URL="http://127.0.0.1:20087"
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
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/smallphone-core/status"
```

查看服务日志：

```bash
curl -q -fsS --max-time 5 -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/smallphone-core/logs?limit=120"
```

启动、停止、重启或修复服务：

```bash
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/smallphone-core/start"
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/smallphone-core/stop"
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/smallphone-core/restart"
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/smallphone-core/repair"
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

不要直接绕过 service-manager 启动 SmallPhone、CloudCLI、cc-connect、MCP server 或 agent server 的长期进程。

## 显式关闭

用户要求关闭后台时，必须通过 service-manager 停止相关服务。

推荐顺序：

1. 列出服务，确认服务 ID。
2. 停止上层入口，例如 SmallPhone frontend、CloudCLI、AI agent、MCP 服务。
3. 停止桥接服务，例如 cc-connect。
4. 保留或停止 service-manager 取决于用户要求：
   - 用户只要求关闭 AI/后台任务：可以保留 service-manager，方便再次启动。
   - 用户要求“一点不占 CPU 和内存”：在确认没有服务需要托管后，也应停止 service-manager 本身或通过 App 侧关闭运行栈。
5. 再次检查状态和残留进程。

停止动作示例：

```bash
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/cloudcli/stop"
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/smallphone-frontend-beta/stop"
curl -q -fsS --max-time 10 -X POST -K /tmp/openhouse-sm-curl.cfg "$SM_URL/api/v1/services/smallphone-core/stop"
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
