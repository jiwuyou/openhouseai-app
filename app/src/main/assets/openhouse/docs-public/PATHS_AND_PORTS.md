# OpenHouse 路径和端口规范

本文给用户、维护者和 AI agent 使用。它定义 OpenHouse 在 Android、Termux、Ubuntu 和本地 Web 服务之间的稳定路径、端口分段、已使用端口和新 App 选端口规则。

端口不是“写死的真理”。服务应优先读取当前配置、service-manager 注册表、component manifest 或环境变量；本文中的默认端口用于首装、排障、文档和冲突规避。

## 路径层级

OpenHouse 同时存在三层路径。AI 执行命令前必须先判断当前层级：

| 层级 | 常见路径 | 用途 |
| --- | --- | --- |
| Android App / Termux 外层 | `/data/data/com.termux/files/home` | Android 侧 Termux home、bootstrap、proot-distro、救援脚本和 APK assets 解包入口。 |
| Termux prefix | `/data/data/com.termux/files/usr` | Termux 包、proot-distro rootfs、Android 侧可执行文件。 |
| Termux native | `/data/data/com.termux/files/home` | service-manager、pi-agent、pi-web 和 Android-adjacent host control 默认在这里运行。 |
| Ubuntu in Termux | `/root` | 默认开发工作区；SmallPhone、Codex、Claude Code、CloudCLI、AionUi 和用户项目默认在这里运行。 |
| Ubuntu rootfs 真实位置 | `/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu` | Termux 内保存 Ubuntu rootfs 的物理目录；排障时可识别，不要直接改 rootfs 内部文件。 |
| Ubuntu 镜像策略 helper | 最新 `/data/data/com.termux/files/home/.local/share/openhouseai/update-resources/apk-*/bootstrap/scripts/_ubuntu-mirror-policy.sh` | rootfs 与 apt 的 canonical 有序故障转移、错误分类和本次运行 lock；使用时先选择最新资源目录。 |
| Ubuntu canonical apt source | `/etc/apt/sources.list.d/openhouseai-ubuntu.sources` | Ubuntu 内唯一由 OpenHouse 管理的 base source 文件；第三方 PPA 保持独立。 |

快速判断：

```bash
pwd
echo "$HOME"
cat /etc/os-release 2>/dev/null || true
command -v proot-distro 2>/dev/null || true
```

如果 `$HOME=/root` 且 `/etc/os-release` 是 Ubuntu，当前通常在 Ubuntu 内。如果 `$HOME=/data/data/com.termux/files/home`，当前通常在 Termux 外层。

## 稳定运行路径

运行期文档入口：

```text
/root/openhouse/docs
/root/openhouseai-docs/official
~/openhouseai-docs/official
```

默认内置仓库和 runtime payload 安装到：

```text
/root/smallphoneai-repos/service-manager
/root/smallphoneai-repos/pi-agent
/root/smallphoneai-repos/pi-web
/root/smallphoneai-repos/openhouse-connect
/root/smallphoneai-repos/smallphone-active
/root/smallphoneai-repos/github-config-helper
```

用户代码和数据放在：

```text
/root/smallphoneai-repos/smallphone-home/apps/<app-id>
/root/smallphoneai-repos/smallphone-home/shells/<shell-id>
```

OpenHouse registry 和 AI 文档：

```text
~/.config/openhouseai/components.d
~/.config/openhouseai/service-manager/services.d
~/.config/openhouseai/ai-docs
~/.config/openhouseai/service-manager/config.json
```

不要把开发机路径写进安装脚本，例如 `/root/projects/...` 只适合当前开发工作区，不是用户首次安装后的稳定路径。

## 端口分段

默认只绑定 `127.0.0.1`。需要 Tailscale、局域网或远程访问时，必须由用户明确要求，并重新审查认证、日志和敏感信息边界。

| 范围 | 归属 | 规则 |
| --- | --- | --- |
| `1-1023` | OS / privileged ports | 不给 OpenHouse 本地服务使用。 |
| `1024-19999` | OS、开发工具、第三方默认端口 | 只用于兼容外部工具默认值，例如 OpenCode、SillyTavern、Hermes 上游默认；新 OpenHouse 服务不要主动占用。 |
| `20000-20999` | OpenHouse 控制平面 | service-manager 和控制面 API。 |
| `21000-21999` | OpenHouse 桥接 / transport | cc-connect bridge、management、webclient、webhook 等。前端 App 不要连错到这里。 |
| `22000-22999` | SmallPhone 平台服务 | SmallPhone Core API、前端、兼容后端。 |
| `23000-23099` | 内置或兼容 App | SmallPhone 内置 standalone app、受控浏览器、CloudCLI、Hermes 当前组件目标等。 |
| `23100-23999` | 用户自定义长期 App | 推荐给用户自定义 Web App 和小工具使用；需要先查已用端口。 |
| `24000-24999` | 临时开发 / 测试 | 只用于短时间本地调试，不写进长期 service spec。 |
| `25000-29999` | 可选大型外部工具 / 未来保留 | 例如某些可选 payload 或第三方 WebUI；使用前必须查当前注册表。 |
| `30000-30999` | pi-agent / pi-web | pi 相关 Web runtime 和救援入口。 |
| `31000-49151` | 未来保留 | 新服务不要默认放这里，除非已有文档指定。 |
| `49152-65535` | 系统临时端口 / ephemeral | 让操作系统给测试进程临时分配；不要写入 service-manager 长期服务。 |

## 已使用和保留端口

下表是当前代码和文档审计得到的默认、保留、可选或历史端口。访问前先检查服务是否已注册和运行。

| 端口 | 状态 | 归属 | 说明 |
| --- | --- | --- | --- |
| `20087` | 默认 fallback，可配置 | service-manager | OpenHouse 服务控制 API 默认监听；真实地址以 `~/.config/openhouseai/service-manager/config.json`、`SERVICE_MANAGER_URL` 或 `SMALLPHONEAI_SERVICE_MANAGER_BIND` 为准，不能当作不可变端口。 |
| `21010` | 保留 / 默认 | cc-connect bridge | WebSocket bridge，例如 `ws://127.0.0.1:21010/bridge/ws`。 |
| `21020` | 保留 / 默认 | cc-connect management | management API。 |
| `21040` | 保留 / 默认 | cc-connect webclient / callback | SmallPhone runtime 通过它访问项目 agent；不要作为普通 WebView App 端口。 |
| `22000` | 默认 | SmallPhone Core | SmallPhone API、registry proxy、用户 shell 静态入口，例如 `/api/components`、`/shells/...`。 |
| `22080` | 兼容 / historical | SmallPhone stable frontend | stable 前端兼容入口，不参与当前 SmallPhoneAI app-facing readiness。 |
| `22082` | 默认 | SmallPhone beta frontend | 当前 SmallPhoneAI 默认前端入口。 |
| `22096` | 可选 / compatibility | SmallPhone OpenCode backend | opencode/bun backend 兼容服务；只有存在对应目录和服务定义时才运行。 |
| `23001` | 内置 / reserved | smallphone-standalone-diary | Diary standalone app。 |
| `23002` | 内置 / optional | smallphone-like-girl-source | vocabulary/source-app adapter；源码存在时启用。 |
| `23003` | 内置 / reserved | smallphone-standalone-like-girl | LikeGirl standalone app。 |
| `23004` | 内置 / reserved | smallphone-standalone-album | Album standalone app。 |
| `23008` | 内置 / reserved | smallphone-standalone-like-girl-clone | LikeGirl clone / control test app。 |
| `23080` | 保留 / component target | controlled-browser | 受控浏览器入口端口；当前服务可能是事件驱动 helper，不代表一定有 HTTP server。 |
| `23083` | 默认 | CloudCLI / CC/Codex | Claude Code / CloudCLI / Codex 相关 Web 入口。 |
| `23084` | 默认 component target | Hermes WebUI | OpenHouse component registry 当前 Hermes 目标端口；若手工安装 Hermes，仍以实际服务定义为准。 |
| `23110` | 示例 / reserved when installed | memo-openhouse | `CUSTOM_FRONTEND_AND_APPS.md` 的 OpenHouse Memo 备忘录示例 App 默认端口。 |
| `23120` | 内置 / reserved | GitHub 本地配置助手 | `github-config-helper` 默认端口；这是 APK 内置功能，不是自定义 App 示例。 |
| `30141` | 默认 | pi-web | pi-agent 背后的本地 Web runtime，由 service-manager 托管。 |
| `4096` | 外部 / historical | OpenCode | 老文档和外部 OpenCode 默认端口；不是 OpenHouse 核心默认端口。 |
| `8022` | 本机回环桥 / preferred | Termux native sshd | Ubuntu/proot 回 Termux native 的首选桥接端口，只应在 `127.0.0.1` 上使用，不给普通 Web App 复用；若被其它 app 容器占用，`oh-termux-ensure-sshd` 会选择 `8023-8039` 并写入真实端口文件。 |
| `8000` | 外部默认 / optional | SillyTavern | SillyTavern 上游默认端口；OpenHouse 只在用户安装该能力时注册。 |
| `8787` | 外部默认 / legacy example | Hermes upstream | `HERMES_SETUP.md` 里的手工安装示例端口；当前组件目标优先看 `23084` 或实际 service spec。 |
| `9222` | 调试 / optional | Browser CDP | 浏览器调试端口；只在显式开启调试时使用，不给普通 App 占用。 |

相关保留但未列入默认表的相邻端口：

- `21030`：SmallPhone 前端会把 `21010`、`21020`、`21030`、`21040` 视为 cc-connect 相关端口并阻止误连。
- `25808`：AionUi 可选 payload 的默认端口之一，不属于默认核心运行栈。

## 新 App 选端口规则

用户自定义长期 App 默认从 `23100-23999` 选择端口。备忘录示例 `memo-openhouse` 使用 `23110`。GitHub 配置助手 `github-config-helper` 使用 `23120`，它是 APK 内置功能，不是自定义 App 示例；新 App 不要复用已安装服务的端口。

推荐流程：

1. 先读本文档的已使用端口表。
2. 查询 service-manager 当前注册和运行状态。
3. 查询 SmallPhone registry 中的 component manifest。
4. 优先选择 `23100-23999` 中尚未使用的端口。
5. 临时调试选择 `24000-24999`，调试结束后释放，不写进长期服务。
6. 长期服务必须注册到 service-manager，并在 ServiceSpec 中声明 `HOST`、`PORT`、health check 和 tags。

排查端口占用：

```bash
ss -ltnp | rg ':(23110|23120|23083|30141)\b' || true
service-manager list 2>/dev/null || true
service-manager status <service-id> 2>/dev/null || true
```

注册长期 App 时，component manifest 只写入口 URL、图标、服务引用和 AI 元数据；启动命令、端口环境变量和 health check 写在 service-manager ServiceSpec 里。

## 读取当前 service-manager endpoint

不要把 `20087` 当作不可变端口。排障脚本应先解析配置和环境变量，最后才 fallback 到默认值：

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
curl -fsS --max-time 2 "${SM_URL%/}/api/v1/health"
```

## 端口文档更新规则

新增长期服务时必须同步更新：

- ServiceSpec 的 `env.PORT`、health URL 和 tags。
- component manifest 的 `serviceManager.services[*].port` 和入口 URL。
- 本文档的已使用端口表。
- 相关 App 指南或排障文档。

如果只是 fallback、历史端口、上游默认端口或可选外部工具端口，必须在状态列标清楚，避免 AI 把它误认为 OpenHouse 核心固定端口。
