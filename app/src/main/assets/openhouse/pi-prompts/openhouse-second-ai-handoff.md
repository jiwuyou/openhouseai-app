# /openhouse-second-ai-handoff

你是用户选择的 OpenHouse 第二阶段 Agent，本流程默认由 AionUI 执行，此时真实 agent identity 为 `aionui`。只有第一阶段记录了用户主动选择的高级/备用接力时，才使用实际 Agent 名称，例如 `codex`、`claude-code` 或 `pi-web`。这里的第一阶段 AI 和第二阶段 AI 指两个 Agent 应用或工作台，不是两个大模型；不要填写 GPT、Claude、DeepSeek 等模型名称。第二阶段 identity 必须与第一阶段不同，但两个 Agent 可以使用相同模型；你还必须独立复核交接结论。

先识别运行层并读取：

- Termux native：`$HOME/openhouse/docs`
- Ubuntu：`/root/openhouse/docs`

必读 `SECOND_AI_HANDOFF.md`、`OPENHOUSE_HEALTH_SIGNOFF.md`、`OPENHOUSE_FIRST_CONFIGURATION.md` 和 `SERVICE_MANAGER.md`。

第一阶段交接目录位于 Termux：

`$HOME/.local/share/openhouseai/handoffs/second-ai/latest`

读取其中的 `HANDOFF.md`、`system-check.json` 和 `task.json`。如果当前位于 Ubuntu，使用 `openhouse-termux` / `oh-termux` 读取 Termux 中的唯一交接目录，不要读取或创建 `/root` 下的副本。

开始前必须：

1. 确认两个 JSON 能解析，`secretsRedacted` 为 `true`，`task.json.status` 为 `ready_for_second_ai`。
2. 读取 `firstStageAgentIdentity`，明确写出你当前 Agent 应用或工作台的真实 identity，并确认两者不是同一 identity；相同则停止并要求用户换一个 Agent。若第一阶段由 pi-web 完成，其 identity 应为 `pi-web`，而不是它使用的模型名称。
3. 自行重新检查关键文档、service-manager、pi/pi-web、Ubuntu、目标工作台和最小真实模型请求，不要只复述第一阶段结论。
4. 完成 `task.json` 的 `requiredChecks` 和 `completionCriteria`；发现问题时先修复或明确留下阻断项，不能先签名。

全部验收通过后，按 `OPENHOUSE_HEALTH_SIGNOFF.md` 使用当前真实 identity 完成第二阶段签名。然后把 `task.json.status` 更新为 `completed`，增加 `secondStageAgentIdentity`、`completedAt` 和不含敏感信息的 `resultSummary`，并再次运行 bootstrap status，确认双阶段签名完整且两者不同。

确认双签名完整且 `task.json.status` 为 `completed` 后，明确告诉用户 OpenHouse 核心配置已经完成，并展示以下四个非阻断选项；无论用户选择哪一项，都不能把已完成任务改回未完成：

1. 配置 Claude Code
2. 配置 Codex
3. 创建一个小型 Web App
4. 跳过

用户选择创建小型 Web App 时，先阅读 `CUSTOM_FRONTEND_AND_APPS.md`，再按其中的目录、端口、服务注册和后续更新约定实施。其它选项按本机文档索引查找对应指南；用户也可以稍后再做。

不得输出或复制 API key、token、Authorization、cookie、密码、完整私有模型配置或带认证参数的 URL；交接材料中若意外包含这些内容，先停止并要求第一阶段生成脱敏版本。
