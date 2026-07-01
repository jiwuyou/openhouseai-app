# 可选外部工具手册

默认核心能力是 service-manager、pi、pi-web、Codex CLI、Claude Code、CloudCLI 和 openhouse-connect。

Operit、OpenCode、Reasonix、Hermes 不作为 APK 内置脚本、payload、插件入口或默认服务打包。本文只作为产品手册，供 pi、pi-web 或 AI 在用户明确要求时参考外部安装和配置；执行前应优先确认工具当前官方文档和版本要求。

## 通用原则

- 只在 Ubuntu 侧安装可选 AI 工具。
- 不要从 APK 资源目录寻找 OpenCode、Reasonix 或 Hermes 安装脚本。
- 不要假设这些工具已经被 service-manager 管理。
- 如果用户希望后台托管，需要该工具提供独立的 `scripts/register-service.sh`，或由维护者新增 service-manager 服务注册清单。
- 不要把 API key 写入仓库、APK 资源、共享文档、日志或截图。

## OpenCode

OpenCode 是可选外部工具，不属于默认核心栈。默认推荐先使用 Codex CLI、Claude Code 和 CloudCLI。

参考安装流程：

```bash
cd ~
node --version
npm --version
npm install -g opencode-ai
opencode --version
```

配置方式以 OpenCode 当前版本为准。常见流程是在 OpenCode 界面或命令行中连接模型供应商，再按提示填写 API key 或登录账号。

AI 处理 OpenCode 请求时应先检查：

```bash
command -v opencode
opencode --version
```

如果命令不存在，不要把它判定为系统损坏，只提示这是可选外部工具。

## Reasonix

Reasonix 是可选外部工具，不属于默认核心栈。默认安装完成条件不依赖 Reasonix。

参考安装流程：

```bash
cd ~
node --version
npm --version
npm install -g reasonix
reasonix --version
```

如果用户要使用 DeepSeek，可按 Reasonix 当前版本文档配置。旧版常见配置文件路径是：

```text
~/.reasonix/config.json
```

不要在产品手册中保存真实 API key。AI 可以辅助生成本机配置，但必须让用户自行输入密钥，并避免写入日志。

AI 处理 Reasonix 请求时应先检查：

```bash
command -v reasonix
reasonix --version
```

如果命令不存在，不要把它判定为系统损坏，只提示这是可选外部工具。

## Hermes

Hermes 不再作为 APK 内置 payload 打包，也没有默认 `hermes.tgz`。默认首页和默认后台服务不指向 Hermes。

如果用户明确要求安装 Hermes，需要外部提供当前 Hermes 仓库、发布包或安装说明。AI 不应假设 APK 内有 Hermes 包。

推荐接入流程：

1. 在 Ubuntu 侧按 Hermes 当前项目说明完成安装。
2. 确认可执行入口、工作目录、端口、日志路径和停止方式。
3. 若需要后台管理，新增独立 service-manager 注册脚本或服务清单。
4. 若需要出现在 OpenHouseAI 菜单，新增独立 `components.d/*.json` 注册项。

注册完成后再检查：

```bash
service-manager list
service-manager status
```

如果没有注册项，不要把 Hermes 显示为内置功能。

## 给 AI 的判断规则

- 默认核心问题：优先检查 service-manager、pi、pi-web、Codex CLI、Claude Code、CloudCLI、openhouse-connect。
- 可选外部工具问题：先确认用户明确选择了 OpenCode、Reasonix 或 Hermes，再按本文指导检查。
- 安装失败时：不要回退默认核心栈，不要修改 APK 内置 manifest，只在用户环境中处理外部工具。
- 文档过期时：以工具当前官方文档为准，并把差异反馈给用户。
