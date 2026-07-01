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
