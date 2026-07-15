# 可选外部工具和构建边界

默认核心能力是 service-manager、pi-agent、pi-web、openhouse-connect、SmallPhone 兼容服务和文档/脚本入口。

Codex CLI、Claude Code、CloudCLI 和 Hermes 是 pi-agent 后置引导安装的 AI 工作能力，不再作为首次安装的阻塞项。

Operit 是 Android 侧完整可选构建能力，不是默认核心运行时。`withOperit` flavor 包含完整 Operit feature/module、宿主桥接和 Android 入口；`withoutOperit` flavor 不依赖、不暴露 Operit。两个 flavor 的包名都保持 `com.termux`，不能共存，只能同签名且 `versionCode` 单调递增时互相升级或替换。

Operit 不是 Ubuntu payload，也不替代 OpenHouse/Pi/AionUi。OpenCode、Reasonix、Hermes 不作为 APK 内置脚本、payload、插件入口或默认服务打包。本文只作为产品手册，供 pi-agent 或 AI 在用户明确要求时参考外部安装和配置；执行前应优先确认工具当前官方文档和版本要求。

Hermes 是一个例外：它可以出现在 pi-agent 新建会话的默认新手提示词中，但必须标注为可选高级能力、耗时较久，并引用 `HERMES_SETUP.md`。这不代表 Hermes 已经进入 APK 核心 payload。

## 通用原则

- 只在 Ubuntu 侧安装可选 AI 工具。
- 不要在 Ubuntu 侧寻找 Operit payload；Operit 的完整实现属于 Android `withOperit` flavor。
- 不要从 APK 资源目录寻找 OpenCode 或 Reasonix 安装脚本。Hermes 只有 `/root/openhouse/scripts/install-hermes.sh` 这个后置准备入口，不代表 APK 内置完整 Hermes payload。
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

Hermes 不作为 APK 内置 payload 打包，也没有默认 `hermes.tgz`。它可以作为 pi-agent 默认新手提示词中的可选高级任务出现，但默认后台服务不预置 Hermes。

如果用户明确要求安装 Hermes，优先阅读 `HERMES_SETUP.md`。AI 不应假设 APK 内有 Hermes 包，也不应污染 OpenHouse 主 Node/Python 环境。

推荐接入流程：

1. 在 Ubuntu 侧使用 `uv` 创建独立环境。
2. 按 `https://github.com/nesquena/hermes-webui` 当前说明安装 Hermes WebUI。
3. 确认可执行入口、工作目录、端口、日志路径和停止方式。
4. 若需要后台管理，新增独立 service-manager 服务清单。
5. 若需要出现在 OpenHouseAI 菜单，新增独立 `components.d/*.json` 注册项。

注册完成后再检查：

```bash
openhouse-system validate
openhouse-system check hermes 2>/dev/null || true
```

如果没有对应 subject，先按 `SERVICE_MANAGER.md` 的 REST API 模板查询 `/api/v1/services` 和 `/api/v1/services/<service-id>/status`。如果没有注册项，不要把 Hermes 显示为内置服务。

## 给 AI 的判断规则

- 默认核心问题：优先检查 service-manager、pi-agent、pi-web、openhouse-connect、SmallPhone 兼容服务和 `/root/openhouse/scripts/check-ai-tools.sh`。
- 可选外部工具问题：先确认用户明确选择了 OpenCode、Reasonix 或 Hermes，再按本文指导检查。
- Operit 问题：先确认当前 APK 是 `withOperit` 还是 `withoutOperit`，不要在 Ubuntu 侧安装或修复 Operit。
- 安装失败时：不要回退默认核心栈，不要修改 APK 内置 manifest，只在用户环境中处理外部工具。
- 文档过期时：以工具当前官方文档为准，并把差异反馈给用户。
