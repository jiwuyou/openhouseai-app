# pi-agent 首次使用

这是给 pi-web 首次配置提示词使用的稳定小写入口。完整说明见同目录：

- `OPENHOUSE_FIRST_CONFIGURATION.md`
- `AI_TOOL_POSTINSTALL.md`
- `START_HERE.md`
- `AI_AGENT_REFERENCE.md`

pi-agent 首次使用的目标不是让用户再手工完成一堆配置，而是让 AI 先阅读 `/root/openhouse/docs`，理解 OpenHouse 的能力、架构、路径和排障入口，然后带用户完成后置能力安装与模型配置迁移。

推荐流程：

1. 先确认 `service-manager`、`pi-web`、`pi-agent` 是否可用。
2. 阅读 `openhouse-overview.md`、`service-manager.md`、`model-config-migration.md`、`cloudcli-claude-code-setup.md`、`troubleshooting.md`。
3. 执行 `/root/openhouse/scripts/check-ai-tools.sh`，判断 Codex、Claude Code、CloudCLI、Hermes 是否已安装。
4. 如果用户需要对应能力，再执行 `/root/openhouse/scripts/install-codex.sh`、`install-claude-code.sh`、`install-cloudcli.sh` 或 `install-hermes.sh`。
5. 迁移 pi-web 已测通的大模型配置时，不要明文输出 key/token，不要把同一密钥在不同协议间机械复制。
6. CloudCLI / Claude Code 配置完成后，必须测通网页侧调用，而不是只检查命令存在。

如果文档没有覆盖当前版本或 provider，pi-agent 应主动联网检索官方文档、项目 README、issue 和 release。

