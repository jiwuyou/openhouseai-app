# OpenHouse 首次配置标准流程

这是给 pi-web 首次配置提示词使用的稳定小写入口。`pi-agent` 可以作为候选运行时；第一阶段和第二阶段指两个 Agent 应用或工作台，不是两个大模型，两个 Agent 可以使用相同模型。通过 pi-web 执行任务时，`pi-web` 可以作为当前 Agent identity。完整说明见同目录：

- `OPENHOUSE_FIRST_CONFIGURATION.md`
- `AI_TOOL_POSTINSTALL.md`
- `START_HERE.md`
- `AI_AGENT_REFERENCE.md`

pi-web 首次使用的目标不是让用户再手工完成一堆配置，而是让当前会话中的 Agent 先阅读 `$HOME/openhouse/docs`，理解 OpenHouse 的能力、架构、路径和排障入口，然后检查系统、真实测通 AionUI 并生成接力任务。标准入口是 `/openhouse-first-config`；identity 使用 Agent 应用或工作台名称，不使用模型名称。其它第二阶段 Agent 选择保留为高级或备用路径。

推荐流程：

1. 先确认 `service-manager`、`pi-web`、`pi-agent` 是否可用。
2. 阅读 `openhouse-overview.md`、`SERVICE_MANAGER.md`、`OPENHOUSE_FIRST_CONFIGURATION.md`、`MODEL_API_SETUP.md`、`model-config-migration.md`、`CLOUDCLI_CLAUDE_CODE.md`、`TROUBLESHOOTING.md`。
3. 在 AionUI 保存模型配置，确认协议、Base URL、model id 可用，并通过健康检查或真实对话得到有效响应。
4. 按 `SECOND_AI_HANDOFF.md` 生成交接文件和第一阶段签名，再由 AionUI 独立复核并完成第二阶段签名。
5. 双签和 `task.json.status=completed` 后，提供配置 Claude Code、配置 Codex、创建小型 Web App、跳过四个选项；这些任务不阻断首次配置完成。
6. 用户选择 Claude Code 或 Codex 时，再执行对应后置安装和配置；选择小型 Web App 时阅读 `CUSTOM_FRONTEND_AND_APPS.md`。
7. 不要明文输出 key/token，不要把同一密钥在不同协议间机械复制。

如果文档没有覆盖当前版本或 provider，pi-agent 应主动联网检索官方文档、项目 README、issue 和 release。

## 定位

pi-agent 是一个可选的本地 agent 运行时和工作台。它可以承载首次配置、系统说明和配置迁移，但不是唯一主工作台，也不代表第一阶段或第二阶段必须使用它。

当前会话中的 AI 应先帮助用户完成这些事情：

1. 理解 OpenHouse 是什么。
2. 确认 `/root/openhouse/docs` 可读。
3. 确认 service-manager、pi-agent、pi-web、Ubuntu 和用户实际选择工作台的状态。
4. 让用户提供模型配置需要的信息。
5. 只在用户需要时，把模型配置迁移到所选择的工作台。
6. 至少测通一个身份不同的第二阶段 AI。
7. 告诉用户后续可以选择 Claude Code、Codex、Hermes Web、pi-web 或其它开源项目作为主工作台。

## 用户需要提供的信息

不要要求用户理解所有配置格式。首次配置时只要求用户提供：

- `base_url`
- `key` 或 `token`
- `model id`
- provider 名称或协议类型，如果用户知道

如果 OpenHouseAI / AionUi 已经配置过模型，优先读取它的探测结果和健康检查结果，而不是重复要求用户解释协议。AionUi 关键字段是 `platform`、`name`、`base_url`、`api_key`、`models[]`、`model_protocols`、`model_health`。

如果用户不知道协议类型，pi-agent 应根据文档和联网检索判断，而不是猜测。DeepSeek、中转站和 OpenAI-compatible 服务常见情况是同一个 key 支持多个 endpoint，但 Claude Code、Codex 和 CloudCLI 需要的配置并不相同。

## `/openhouse-first-config` 标准提示词目标

当前会话中的 AI 运行标准 pi 模板后应做这些事：

1. 先阅读 `$HOME/openhouse/docs/ai-reference-index.md`；在 Ubuntu/AionUI 中使用 `/root/openhouse/docs/ai-reference-index.md`。
2. 再阅读 `openhouse-overview.md`、`OPENHOUSE_FIRST_CONFIGURATION.md`、`MODEL_API_SETUP.md`、`model-config-migration.md`、`CLOUDCLI_CLAUDE_CODE.md`、`codex-setup.md` 和 `SERVICE_MANAGER.md`。
3. 内测默认配置 AionUI 作为第二阶段 Agent；只有 AionUI 不可用或用户明确要求时，才选择其它独立工作台走高级/备用路径。
4. 确认 AionUI 页面可打开、模型配置已保存、协议/Base URL/model id 可用，并通过健康检查或真实对话得到有效响应。
5. 配置前先说明需要用户提供哪些信息。
6. 配置时优先使用 AionUi 自带探测、OpenHouse 脚本、cc-switch 等确定性工具，不靠自由发挥手写配置。
7. 配置后必须实际测试。
8. 失败时先查文档和日志，再联网搜索。

## 推荐给 Claude Code 的交接话术

用户准备把 OpenHouse 交给 Claude Code 时，使用这段话即可：

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

这段话只要求 Claude Code 阅读文档，不在复制内容中重新定义 Claude Code。

## 成功标准

pi-agent 首次配置完成的标准是：

- 用户理解 pi-agent 是配置助手，不是唯一主工作台。
- service-manager 可以查询核心服务。
- 仅在需要迁移时，用户提供的模型配置已经迁移到目标工具。
- AionUI 已完成真实请求并收到有效回复。
- 用户选择的工作台或对应入口可访问。
- 当前 AI 告诉用户后续可以从哪里继续使用系统。
- AionUI 已收到交接任务，或用户已经拿到可直接复制给 AionUI 的接力提示词。
- 默认由 AionUI 完成第二阶段独立复核和签名，`task.json.status` 为 `completed`。
- 完成后已提供配置 Claude Code、配置 Codex、创建小型 Web App、跳过四个非阻断选项。

如果只写入配置文件但没有实测，不算完成。
