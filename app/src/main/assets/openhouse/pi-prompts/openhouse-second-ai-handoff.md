# /openhouse-second-ai-handoff

你是用户选择的 OpenHouse 第二阶段 Agent。这里的第一阶段 AI 和第二阶段 AI 指两个 Agent 应用或工作台，不是两个大模型。请使用当前 Agent 名称作为真实 agent identity，例如 `codex`、`claude-code`、`aionui` 或 `pi-web`，不要填写 GPT、Claude、DeepSeek 等模型名称。第二阶段 identity 必须与第一阶段不同，但两个 Agent 可以使用相同模型；你还必须独立复核交接结论。

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

不得输出或复制 API key、token、Authorization、cookie、密码、完整私有模型配置或带认证参数的 URL；交接材料中若意外包含这些内容，先停止并要求第一阶段生成脱敏版本。
