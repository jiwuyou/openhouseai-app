# AI 参考索引

本文档给 pi-agent、Claude Code、Codex 和其它 AI 工作台使用。目标是让 AI 知道遇到不同任务时应该先读哪份文档，避免凭记忆猜路径、猜协议或猜修复步骤。

运行期文档目录：

```text
/root/openhouse/docs
```

## 先读什么

首次进入 OpenHouse 环境时，先按顺序阅读：

1. `openhouse-overview.md`
2. `AI_AGENT_REFERENCE.md`
3. `service-manager.md`
4. `troubleshooting.md`

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
| 终端和路径 | `terminal-guide.md`, `TERMINAL_PROFILES.md`, `ENVIRONMENT.md` | 判断 Android、Termux、Ubuntu 层级和跨层调用。 |
| pi-agent 首次配置 | `pi-agent-first-use.md`, `OPENHOUSE_FIRST_CONFIGURATION.md` | 引导用户配置模型和后置 AI 工具。 |
| 模型配置迁移 | `model-config-migration.md`, `MODEL_API_SETUP.md` | 按协议迁移 `base_url`、key/token、model id。 |
| Claude Code / CloudCLI | `cloudcli-claude-code-setup.md`, `CLOUDCLI_CLAUDE_CODE.md` | 安装、配置并测通 CloudCLI 中的 Claude Code。 |
| Codex | `codex-setup.md`, `MODEL_API_SETUP.md` | 配置并实测 Codex。 |
| service-manager | `service-manager.md`, `SERVICE_MANAGER.md` | 注册、启动、停止、修复长期服务。 |
| 前台保活 | `openhouse-runtime-policy.md` | 理解 Android 和 service-manager 的分工。 |
| 停止运行栈 / 全部退出 | `openhouse-exit-all.md` | 明确停止范围、界面行为、保留范围和恢复行为。 |
| 故障排查 | `troubleshooting.md`, `RECOVERY.md` | 按症状选择最小修复步骤。 |
| cc-switch | `cc-switch.md` | 作为模型配置执行器使用，不把它当服务。 |
| 权限和降级 | `permissions.md` | 区分必需权限、可选权限和缺失时降级。 |
| 失败边界 | `failure-boundaries.md` | 判断何时自动修复，何时提示重置或重装。 |

## 默认决策规则

- 开发、项目命令、pi-agent、pi-web、Claude Code、Codex、CloudCLI 默认在 Ubuntu 内执行。
- Termux 外层只处理底座、proot-distro、Android 桥、安装引导和救援。
- 后台长期服务必须优先通过 service-manager 管理。
- App 前台默认保持 `service-manager`、`smallphone`、`pi-agent`、`cloudcli` 可用。
- `pi-agent` 是首次配置助手，不是唯一主工作台。
- `cc-switch` 是配置工具箱，不是长期服务。
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
/root/openhouse/docs/cloudcli-claude-code-setup.md
/root/openhouse/docs/service-manager.md
/root/openhouse/docs/troubleshooting.md

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
