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
5. 迁移 OpenHouseAI / AionUi 或 pi-web 已测通的大模型配置时，优先参考 AionUi 自带协议检测、模型列表拉取、Key 测试和模型健康检查结果。
6. 不要明文输出 key/token，不要把同一密钥在不同协议间机械复制。
7. 配置 CloudCLI 中的 Claude Code 时，可以可选安装并调用 `cc-switch` 作为 provider 配置执行器，但最终仍必须测通网页侧调用，而不是只检查命令存在。

如果文档没有覆盖当前版本或 provider，pi-agent 应主动联网检索官方文档、项目 README、issue 和 release。

## 定位

pi-agent 是首次配置助手、系统说明助手和配置迁移执行者。它不是唯一主工作台，也不要求用户以后都在 pi-agent 中工作。

pi-agent 应先帮助用户完成这些事情：

1. 理解 OpenHouse 是什么。
2. 确认 `/root/openhouse/docs` 可读。
3. 确认 service-manager、pi-agent、pi-web、smallphone 和 cloudcli 的状态。
4. 让用户提供模型配置需要的信息。
5. 把模型配置迁移到 Claude Code、Codex 或 CloudCLI。
6. 至少测通 Claude 或 Codex 之一。
7. 告诉用户后续可以选择 Claude Code、Codex、Hermes Web、pi-web 或其它开源项目作为主工作台。

## 用户需要提供的信息

不要要求用户理解所有配置格式。首次配置时只要求用户提供：

- `base_url`
- `key` 或 `token`
- `model id`
- provider 名称或协议类型，如果用户知道

如果 OpenHouseAI / AionUi 已经配置过模型，优先读取它的探测结果和健康检查结果，而不是重复要求用户解释协议。AionUi 关键字段是 `platform`、`name`、`base_url`、`api_key`、`models[]`、`model_protocols`、`model_health`。

如果用户不知道协议类型，pi-agent 应根据文档和联网检索判断，而不是猜测。DeepSeek、中转站和 OpenAI-compatible 服务常见情况是同一个 key 支持多个 endpoint，但 Claude Code、Codex 和 CloudCLI 需要的配置并不相同。

## 首次提示词目标

pi-agent 新会话的首次提示词应让 AI 做这些事：

1. 先阅读 `/root/openhouse/docs/ai-reference-index.md`。
2. 再阅读 `openhouse-overview.md`、`model-config-migration.md`、`cloudcli-claude-code-setup.md`、`codex-setup.md` 和 `service-manager.md`。
3. 确认用户要优先使用 Claude 还是 Codex。
4. 如果用户没有偏好，优先选择最容易测通的一项。
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
/root/openhouse/docs/cloudcli-claude-code-setup.md
/root/openhouse/docs/service-manager.md
/root/openhouse/docs/troubleshooting.md

阅读后记住这些文档有什么用，什么时候去阅读这些文档，这些文档在哪里。再和我聊天。
```

这段话只要求 Claude Code 阅读文档，不在复制内容中重新定义 Claude Code。

## 成功标准

pi-agent 首次配置完成的标准是：

- 用户理解 pi-agent 是配置助手，不是唯一主工作台。
- service-manager 可以查询核心服务。
- 用户提供的模型配置已经迁移到目标工具。
- Claude 或 Codex 至少一个完成真实请求并收到回复。
- CloudCLI 页面或对应入口可访问。
- pi-agent 告诉用户后续可以从哪里继续使用系统。

如果只写入配置文件但没有实测，不算完成。
