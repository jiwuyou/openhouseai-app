# OpenHouseAI / WuxianPi 双运行时架构

## 产品版本

| Edition | applicationId | Pi 位置 | 与官方 Termux 共存 |
| --- | --- | --- | --- |
| All-in-One | `com.termux` | APK 内置 Termux native runtime | 否 |
| WuxianPi Native | `com.wuxianpi` | 用户现有的官方 Termux | 是 |

两个版本共享 OpenHouse 桌面、Apps、Compose AI UI、Web UI、Android 工具桥和 Pi 原始会话格式。Android 主进程不加载 Pi 或 QuickJS；Pi Rust 只在 Termux 子进程中运行。

## Agent 数据通道

```text
Compose UI / pi-web
        ⇅ WebSocket（一帧一条原始 Pi JSON）
openhouse-pi-runtime
        ⇅ stdin/stdout JSONL
pi --mode rpc
```

`openhouse-pi-runtime` 只负责进程、租约、认证、透明转发和恢复，不实现 Agent 循环、工具轮数、自动续跑、提示词或工具失败策略。工具失败作为 Pi ToolResult 返回，只有 Pi 的 `agent_end` 才结束一轮。

Web 浏览器不能为 WebSocket 设置 Authorization Header。因此 pi-web 使用同源 `/ws/pi` 代理：浏览器与代理之间、代理与网关之间都原样传输 Pi JSON；Node 代理仅从本机 token 文件读取 Bearer Token并在连接网关时注入。长期 token 不进入查询参数、浏览器 JavaScript 或 localStorage。

## 会话并行与控制权

- 每个活动对话对应一个独立 `pi --mode rpc` 进程。
- 不同对话可以并行执行模型和普通工具，不共享 QuickJS Runtime 或 executor。
- 同一对话只有一个控制租约。另一个客户端收到 HTTP 409，必须由用户显式 takeover。
- 浏览器获得租约的顺序为 `POST /admin/v1/sessions`、`POST /admin/v1/leases`、连接 `/ws/rpc/{leaseId}`。
- 断线后网关保留短暂恢复窗口；客户端重连后发送 `get_state` 和 `get_messages`，不重新发送用户消息。
- 无客户端且空闲超时后，网关回收对应 Pi 进程；JSONL 会话不受影响。

## Android 工具

`runtime/extensions/openhouse-tools/` 是 Pi 扩展，网关用 `--extension` 加载。扩展通过 localhost 调用 Android Bridge：

```text
POST http://127.0.0.1:<bridge-port>/v1/tools/<toolName>
Authorization: Bearer <bridge-token>
```

Bridge 返回结构化工具结果；非零退出、权限拒绝和环境缺失均返回错误结果而不是终止对话。截图、无障碍和系统弹窗等共享手机状态的操作由 Bridge 串行，其余不同会话工具可并行。

## WuxianPi Native 首次部署

Native APK 启动只绑定 localhost 的一次性安装服务，并显示一行 Termux 命令。命令从 APK 服务下载 `wuxianpi-native-install.tar`，校验其中 `pi-runtime.tar` 后运行安装器。最终文件位于：

```text
$HOME/.local/share/openhouseai/runtime/
$HOME/.local/bin/openhouse-pi
$HOME/.local/bin/openhouse-pi-runtime-start
$HOME/.config/openhouseai/
```

安装器不覆盖用户的 `pi` 命令、shell rc 或其他 Termux 项目。All-in-One 使用同一个 `pi-runtime.tar`，但由 APK bootstrap 自动安装，无需手工命令。

Native APK 内实际嵌入的EXTERNAL资产是 `native-app/src/main/assets/openhouse-runtime/runtime-aarch64.tgz`。使用 `.tgz` 是为了避免 AAPT 对 `.gz` 资产强制解压和改名。解包根必须包含可执行 `install.sh`，并同时包含 ARM64 `pi`、`openhouse-pi-runtime`、OpenHouse tools扩展、脚本和校验metadata。当前版本只支持 `arm64-v8a`；其他ABI必须明确显示 unsupported，不能伪装成下载404。

## 构建

Pi Rust 源固定为 commit `ad719ad3d42173be9293a020492b7d10f85c95fe`。ARM64 Android 二进制默认在 SSH 主机 `phonetermux` 上原生编译：

```bash
./scripts/build-runtime.sh
./scripts/build-all-in-one.sh
./scripts/build-native.sh
```

构建机必须能访问 `/root/projects/pi_agent_rust` 和 `runtime/extensions/openhouse-tools/`。可用 `PI_RUST_BUILD_SSH` 更换 ARM64 Termux 构建主机。CI 已提供经过验证的二进制时，可设置 `PI_RUST_BINARY` 和 `PI_GATEWAY_BINARY`，脚本仍会检查 ARM64 ELF、生成校验和并更新 manifests；脚本不会接受占位二进制。

Web UI 源在 `web/pi-web/`。其 Agent 通道为 `PiWebSocketTransport`；旧 `/api/agent` 路由只返回 410，不再创建 TypeScript AgentSession。

## 发布验证

```bash
bash -n scripts/build-pi-rust-payload.sh scripts/build-runtime.sh \
  scripts/build-all-in-one.sh scripts/build-native.sh
./scripts/validate-openhouse-payloads.sh
cd web/pi-web
npm ci
npm run lint
npx tsc --noEmit
```

正式发布还必须替换仓库当前公开测试签名，并分别在无官方 Termux和已安装官方 Termux的真机上验证安装、进程回收、租约冲突、API 欠费错误、工具错误和断线恢复。
