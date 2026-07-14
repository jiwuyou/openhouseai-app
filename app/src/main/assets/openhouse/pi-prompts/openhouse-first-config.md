# /openhouse-first-config

你正在通过 pi-web 完成 OpenHouse 第一阶段配置。这里的第一阶段 AI 和第二阶段 AI 指两个 Agent 应用或工作台，不是两个大模型；pi-web 本身就是当前第一阶段的 Agent identity，填写 `firstStageAgentIdentity` 时使用 `pi-web`，不要填写 GPT、Claude、DeepSeek 等模型名称。Agent 使用的模型只是运行配置，两个不同 Agent 可以使用相同模型。

先识别当前运行层，再读取同一份 OpenHouse 官方文档：

- Termux native：`$HOME/openhouse/docs`
- Ubuntu：`/root/openhouse/docs`

至少阅读：

- `START_HERE.md`
- `OPENHOUSE_FIRST_CONFIGURATION.md`
- `SECOND_AI_HANDOFF.md`
- `OPENHOUSE_HEALTH_SIGNOFF.md`
- `SERVICE_MANAGER.md`

配置 AionUI、模型 API 或 CloudCLI 时按需阅读：

- `MODEL_API_SETUP.md`
- `model-config-migration.md`
- `CLOUDCLI_CLAUDE_CODE.md`

按文档完成第一阶段检查，确认固定安装、文档、service-manager、pi/pi-web、Ubuntu 和已安装工作台的真实状态。不要把“命令存在”当成健康；需要模型能力时必须完成最小真实请求。若有非阻断缺项，记录到交接文件后继续；只有固定安装、文档、service-manager 或第二阶段 AI 均不可用时才停止。

帮助用户选择并准备一个独立的第二阶段 Agent 应用或工作台。第二阶段不固定为 AionUI、Claude Code、Codex、Hermes 或 pi；identity 应填写 `aionui`、`claude-code`、`codex`、`hermes`、`pi-web` 等 Agent 名称，而不是模型名称。第二阶段 identity 必须与第一阶段不同，但可以使用相同模型。不要替第二阶段 Agent 做独立复核或签名。

第一阶段完成后，把脱敏交接材料写入 Termux 目录：

`$HOME/.local/share/openhouseai/handoffs/second-ai/latest`

目录必须包含：

- `HANDOFF.md`：第一阶段 identity、已完成事项、阻断项、第二阶段动作和验收标准。
- `system-check.json`：有效 JSON，至少包含 `schema`、`generatedAt`、`firstStageAgentIdentity`、`runtimeLayer`、`checks`、`warnings`、`secretsRedacted`；`secretsRedacted` 必须为 `true`。
- `task.json`：有效 JSON，至少包含 `schema`、`status`、`firstStageAgentIdentity`、`requireDifferentSecondStageIdentity`、`objective`、`requiredChecks`、`completionCriteria`；初始 `status` 为 `ready_for_second_ai`，`requireDifferentSecondStageIdentity` 为 `true`。

在 `latest` 中先写同名临时文件，确认三个文件存在、两个 JSON 能解析且不含敏感信息后再用 `mv` 替换正式文件。目录权限设为 `700`，文件权限设为 `600`。如果当前位于 Ubuntu，使用 `openhouse-termux` / `oh-termux` 写入同一个 Termux 目录，不要在 `/root` 下建立第二套事实源。

签名前从 APK 版本化资源目录定位真实 bootstrap 并执行状态检查。Termux native 使用：

`resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo '未找到可用的 APK bootstrap 资源' >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh status`

Ubuntu 使用：

`openhouse-termux exec -- 'resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name "apk-*" | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh status'`

复核通过后，按 `OPENHOUSE_HEALTH_SIGNOFF.md` 使用当前真实 agent identity 完成第一阶段签名；确认状态要求第二阶段签名，而不是错误地报告全部完成。

最后必须单独输出这一句可复制文本，并把同一句写入 `HANDOFF.md`：

`请作为与第一阶段 AI（<firstStageAgentIdentity>）不同的 OpenHouse 第二阶段 AI，先阅读本机 OpenHouse 文档，再读取 Termux 的 $HOME/.local/share/openhouseai/handoffs/second-ai/latest（若你在 Ubuntu，请通过 openhouse-termux 访问）中的 HANDOFF.md、system-check.json 和 task.json，独立复核、完成任务，并仅在全部验收通过后以你的真实 identity 完成第二阶段签名。`

不得在文档、交接文件、日志或回复中写出 API key、token、Authorization、cookie、密码、完整私有模型配置或带认证参数的 URL。
