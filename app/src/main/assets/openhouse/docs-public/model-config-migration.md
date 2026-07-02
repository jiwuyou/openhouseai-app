# 模型配置迁移

这是给 pi-agent 使用的稳定小写入口。完整说明见同目录：

- `MODEL_API_SETUP.md`
- `OPENHOUSE_FIRST_CONFIGURATION.md`
- `CLOUDCLI_CLAUDE_CODE.md`
- `GITHUB_NETWORK_MIRRORS.md`

pi-web 已经测通的大模型配置可以作为后置工具配置的参考，但不能简单照抄。迁移时必须确认 provider、base URL、协议类型、model id、headers 和密钥来源。

迁移原则：

- 不在回复、日志、截图或文档中明文输出 API key、token、authorization header。
- 先判断协议：OpenAI Chat、OpenAI Responses、Anthropic Messages、Gemini Native 或代理协议。
- 同一个 provider 可能支持多种协议。DeepSeek 等服务常见情况是同一个密钥可以用于不同 endpoint，但目标工具需要的配置格式不一定相同。
- 目标是让目标工具真实可用。CloudCLI / Claude Code 需要测通网页侧模型调用；Codex 或 Claude Code CLI 需要至少完成一次最小请求。
- 如果当前工具版本和文档不一致，应联网检索最新官方文档、README、release note 和 issue。

常用参考路径：

- `/root/openhouse/docs/MODEL_API_SETUP.md`
- `/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md`
- `/root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md`

