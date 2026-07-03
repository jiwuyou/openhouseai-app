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

## 必需输入

迁移模型配置时，至少需要收集：

| 字段 | 含义 | 注意 |
| --- | --- | --- |
| `base_url` | API 入口地址 | 不同协议可能使用不同路径，例如 `/v1`、Anthropic endpoint 或代理 endpoint。 |
| `key/token` | 认证凭据 | 不得明文写入聊天、日志、截图或文档。 |
| `model id` | 实际模型名 | 不能用展示名代替实际模型 ID。 |
| 协议类型 | OpenAI-compatible、Anthropic-compatible、官方协议或中转协议 | 不能只按 provider 品牌判断。 |
| 目标工具 | Claude Code、Codex、CloudCLI、Hermes 或其它 | 不同工具配置格式不同。 |

用户不知道协议类型时，AI 应询问 provider 文档、联网检索或使用工具检测。

## 协议判断

迁移前必须先判断目标协议：

- OpenAI Chat Completions。
- OpenAI Responses。
- Anthropic Messages。
- Claude 官方。
- OpenAI/Codex 官方。
- Gemini Native。
- 中转站自定义协议。

DeepSeek 这类 provider 可能用同一个 key 支持不同协议。不要因为 provider 名称相同就把同一段配置复制到所有工具。

## 迁移流程

1. 从 pi-web 已测通配置中提取 provider、base URL、model id 和协议信息。
2. 对 key/token 做脱敏展示，只在实际配置写入时使用完整值。
3. 根据目标工具选择配置方式。
4. 优先调用 OpenHouse 脚本或 cc-switch 等配置执行器。
5. 写入配置后立即测试。
6. 测试失败时记录脱敏后的错误摘要。
7. 查阅 `troubleshooting.md`，必要时联网检索目标工具官方文档。

## 目标工具要求

### Claude Code / CloudCLI

- 阅读 `cloudcli-claude-code-setup.md`。
- 确认 `/root/.local/bin/claude` 存在且可执行。
- 确认 CloudCLI 使用的端口和 service-manager 注册一致。
- 测试目标是 CloudCLI 页面中的 Claude Code 能实际调用模型。

### Codex

- 阅读 `codex-setup.md`。
- 区分官方 Codex 登录和 OpenAI-compatible API 模式。
- 至少完成一次最小请求或可验证的模型调用。

### cc-switch

- 阅读 `cc-switch.md`。
- cc-switch 可以帮助生成和切换 provider 配置。
- cc-switch 不负责长期运行，也不替代 service-manager。

## 安全要求

禁止输出完整：

- API key
- token
- authorization header
- cookie
- service-manager auth token

日志和诊断只能显示脱敏形式：

```text
sk-****abcd
token: ****abcd
```

不要直接打印包含 `env` 的完整 service JSON。
