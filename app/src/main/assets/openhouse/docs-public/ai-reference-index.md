# AI 参考索引

本文档给 pi-agent、Claude Code、Codex 和其它 AI 工作台使用。目标是让 AI 知道遇到不同任务时应该先读哪份文档，避免凭记忆猜路径、猜协议或猜修复步骤。

运行期文档目录：

```text
/data/data/com.termux/files/home/openhouse/docs
/root/openhouse/docs
```

## 先读什么

首次进入 OpenHouse 环境时，先按顺序阅读：

1. `openhouse-overview.md`
2. `AI_AGENT_REFERENCE.md`
3. `TERMUX_UBUNTU_BRIDGE.md`
4. `OPENHOUSE_SYSTEM.md`
5. `SERVICE_MANAGER.md`
6. `TROUBLESHOOTING.md`

如果你要参与下一轮产品实现，还必须阅读：

1. `implementation-acceptance-checklist.md`
2. `openhouse-install-flow.md`
3. `openhouse-runtime-policy.md`
4. `openhouse-exit-all.md`

## 按任务选择文档

| 任务 | 优先阅读 | 目的 |
| --- | --- | --- |
| 理解产品是什么 | `openhouse-overview.md`, `PRODUCT_OVERVIEW.md`, `CAPABILITIES_MAP.md` | 明确 OpenHouse 是人和 AI 共用的软件平台。 |
| 首次安装链路 | `openhouse-install-flow.md`, `openhouse-cn-network-retry.md` | 了解阶段状态机、重试、国内路径、强制重试边界。 |
| 新用户教学 | `first-use-tutorial.md` | 按界面、箭头、点击主体和跳过规则设计教学。 |
| 终端和路径 | `terminal-guide.md`, `TERMUX_UBUNTU_BRIDGE.md`, `TERMINAL_PROFILES.md`, `ENVIRONMENT.md` | 判断 Android、Termux、Ubuntu 层级和跨层调用。 |
| 路径和端口规范 | `PATHS_AND_PORTS.md`, `SERVICE_MANAGER.md` | 确认稳定安装路径、service-manager endpoint、端口分段、已用/保留端口和新 App 选端口规则。 |
| 首次配置和 AI 接力 | `pi-agent-first-use.md`, `OPENHOUSE_FIRST_CONFIGURATION.md`, `SECOND_AI_HANDOFF.md`, `OPENHOUSE_HEALTH_SIGNOFF.md` | 内测默认 pi-web 第一阶段、AionUI 第二阶段；通用任选 Agent 作为高级/备用路径。 |
| AionUI 模型配置 | `OPENHOUSE_FIRST_CONFIGURATION.md`, `MODEL_API_SETUP.md` | 配置平台、协议、Base URL、模型、Key 测试和模型健康检查。 |
| 模型配置迁移 | `model-config-migration.md`, `MODEL_API_SETUP.md` | 按协议迁移 `base_url`、key/token、model id。 |
| Claude Code / CloudCLI | `CLOUDCLI_CLAUDE_CODE.md`, `MODEL_API_SETUP.md` | 安装、配置并测通 CloudCLI 中的 Claude Code。 |
| Codex | `codex-setup.md`, `MODEL_API_SETUP.md` | 配置并实测 Codex。 |
| GitHub 本地授权和 gh 配置 | `GITHUB_CONFIG_HELPER.md`, `GITHUB_NETWORK_MIRRORS.md` | 复用 GitHub CLI 官方 OAuth 流程，不注册 OAuth App，配置 `gh auth login` 和 `gh auth setup-git`，让同一 Ubuntu 用户和同一 `$HOME` 下的 Codex / Claude Code 直接调用 `git` 和 `gh`。 |
| service-manager | `SERVICE_MANAGER.md` | 注册、启动、停止、修复长期服务。 |
| OpenHouse 系统检查 | `OPENHOUSE_SYSTEM.md`, `SERVICE_MANAGER.md` | 校验和渲染 subjects，按 subject 检查服务、HTTP、路径与技能，并正确解释 skipped/degraded。 |
| 自定义前端或 App | `CUSTOM_FRONTEND_AND_APPS.md`, `PATHS_AND_PORTS.md`, `SERVICE_MANAGER.md` | 生成真实代码，先按规范选择路径和端口，再注册 component manifest 和 service-manager 服务，并按 AI 更新流程维护。 |
| 首次配置后创建小型 Web App | `CUSTOM_FRONTEND_AND_APPS.md` | 作为双签完成后的可选任务创建手机优先的小型本地 Web App。 |
| 前台保活 | `openhouse-runtime-policy.md` | 理解 Android 和 service-manager 的分工。 |
| 停止运行栈 / 全部退出 | `openhouse-exit-all.md` | 明确停止范围、界面行为、保留范围和恢复行为。 |
| 故障排查 | `TROUBLESHOOTING.md`, `RECOVERY.md` | 按症状选择最小修复步骤。 |
| 浏览器、WebView 和页面自动化 | `BROWSER_AND_WEBVIEW.md` | 区分普通 WebView 和受控浏览器，确认页壳动作、App 名显示规则、WebView 保留策略，并使用 `openhouse-browser` 读取页面、点击、填写、截图或执行短流程。 |
| cc-switch | `cc-switch.md` | 作为模型配置执行器使用，不把它当服务。 |
| 权限和降级 | `permissions.md` | 区分必需权限、可选权限和缺失时降级。 |
| 失败边界 | `failure-boundaries.md` | 判断何时自动修复，何时提示重置或重装。 |

## 默认决策规则

- `pi-agent`、`pi-web` 默认在 Termux native 层作为长期服务执行；开发、项目命令、Claude Code、Codex、CloudCLI 默认在 Ubuntu 工作区执行。
- Termux 外层只处理底座、proot-distro、Android 桥、安装引导和救援。
- Termux 到 Ubuntu 使用 `oh-ubuntu-root` / `proot-distro`；Ubuntu 到 Termux 使用 `openhouse-termux`，不要在 Ubuntu 内直接执行 Termux prefix 二进制。
- 后台长期服务必须优先通过 service-manager 管理。
- 新增本地长期 App 前必须先按 `PATHS_AND_PORTS.md` 选择未占用端口，不要占用控制平面、桥接或 SmallPhone 平台端口。
- App 前台默认保持 `service-manager`、`smallphone`、`pi-agent`、`cloudcli` 可用。
- `pi-agent` 是首次配置助手，不是唯一主工作台。
- Agent identity 使用 `pi-web`、`codex`、`claude-code`、`aionui` 等 Agent 应用或工作台名称，不使用模型名称。
- 内测默认由 pi-web 第一阶段和 AionUI 第二阶段完成双签；Claude Code、Codex、小型 Web App 和跳过是完成后的非阻断选项。
- `cc-switch` 是配置工具箱，不是长期服务。
- GitHub 配置助手只做本机授权和环境配置；后续 GitHub 操作应交给 Codex / Claude Code 调用 `git` 和 `gh`。
- 首次教学不进入终端教学。
- 配置模型必须按协议判断，不能只按 provider 名称判断。
- 任何日志、回复和诊断报告不得泄露 key/token。

## 交给 Claude Code 的推荐话术

用户把 OpenHouse 交给 Claude Code 时，只需要让 Claude Code 阅读文档，不要在话术中重新定义 Claude Code 身份：

```text
请先阅读 OpenHouse 的内置文档，了解这个产品的能力、架构和使用方式。

文档目录：
/root/openhouse/docs

请优先查看：
/root/openhouse/docs/openhouse-overview.md
/root/openhouse/docs/pi-agent-first-use.md
/root/openhouse/docs/model-config-migration.md
/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md
/root/openhouse/docs/SERVICE_MANAGER.md
/root/openhouse/docs/TROUBLESHOOTING.md

阅读后记住这些文档有什么用，什么时候去阅读这些文档，这些文档在哪里。再和我聊天。
```

## 搜索策略

如果内置文档和实际版本不一致，AI 应先说明不一致点，再联网检索官方文档、GitHub README、release note 和 issue。搜索时优先使用官方来源；国内网络不稳定时参考 `openhouse-cn-network-retry.md` 和 `GITHUB_NETWORK_MIRRORS.md`。

## 安全红线

不要把以下内容写入回复、日志、文档、截图或 git：

- API key
- auth token
- bearer token
- service-manager auth token
- cookie
- provider secret
- 私有模型配置完整内容

如果必须展示，只能脱敏为 `****abcd` 形式。
