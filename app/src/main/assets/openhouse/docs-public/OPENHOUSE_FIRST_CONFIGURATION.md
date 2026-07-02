# 首次 OpenHouse 配置

本文说明：用户已经在 pi-web 配好可用大模型之后，如何由 `pi-agent` 完成 OpenHouse 的首次配置闭环。

目标不是再让用户手工填很多配置，而是让 `pi-agent` 读取文档、检查后置 AI 工具、迁移模型配置、测通 CloudCLI 中的 Claude Code，然后把用户带到可以真正工作的界面。

## 触发条件

pi-web 检测到：

- 已存在可用模型配置。
- OpenHouse 首次配置尚未完成。

界面应提示：

```text
首次使用 OpenHouse，看到我，请点击我，完成首次配置。
```

用户点击后，pi-web 应在 `/root` 开启新会话，默认开启全部工具，并把首次配置任务交给 `pi-agent`。

## pi-agent 要做什么

`pi-agent` 应按顺序执行：

1. 阅读 OpenHouse 文档索引。
2. 确认当前文档路径。
3. 读取 pi-web 已保存的模型配置。
4. 执行 `/root/openhouse/scripts/check-ai-tools.sh`，判断 Codex、Claude Code、CloudCLI 是否已安装。
5. 按用户目标执行后置安装脚本，例如 `install-claude-code.sh`、`install-cloudcli.sh`。
6. 判断 provider、baseUrl、modelId、协议和凭据来源。
7. 把可用配置迁移到 CloudCLI / Claude Code 所需的位置。
8. 遇到协议差异时，按文档和网络检索确认正确配置。
9. 启动或重启相关服务。
10. 测通 CloudCLI 中的 Claude Code。
11. 向用户介绍基本入口和下一步选择。
12. 给用户 Claude Code 交接提示词，引用 `CLAUDE_CODE_HANDOFF.md`。

## 首先阅读的文档

优先路径：

```text
/root/openhouse/docs
```

必读文档：

```text
/root/openhouse/docs/START_HERE.md
/root/openhouse/docs/CAPABILITIES_MAP.md
/root/openhouse/docs/WORKBENCH_OPTIONS.md
/root/openhouse/docs/AI_TOOL_POSTINSTALL.md
/root/openhouse/docs/MODEL_API_SETUP.md
/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md
/root/openhouse/docs/SERVICE_MANAGER.md
/root/openhouse/docs/GITHUB_NETWORK_MIRRORS.md
/root/openhouse/docs/CLAUDE_CODE_HANDOFF.md
```

如果 `/root/openhouse/docs` 不存在，改用：

```text
/root/openhouseai-docs/official
```

## 模型配置迁移原则

pi-web 的模型配置不能机械复制到 Claude Code。AI 必须理解协议差异。

需要识别：

- provider 名称和别名。
- baseUrl 是否包含 `/v1`、`/chat/completions`、`/responses`、`/messages` 等路径。
- API 协议类型，例如 OpenAI Chat、OpenAI Responses、Anthropic Messages、Gemini Native。
- modelId 是否适合目标工具。
- 凭据是 API key、token、header 还是环境变量。
- CloudCLI / Claude Code 当前版本支持哪种配置格式。

## DeepSeek 示例

DeepSeek 经常使用同一个密钥，但不同工具可能要求不同协议。

常见 pi-web 配置可能是：

```text
provider: deepseek
api: openai-completions
baseUrl: https://api.deepseek.com/v1
modelId: deepseek-chat
```

这适合 OpenAI Chat 兼容接口。若 Claude Code 或 CloudCLI 侧期望 Anthropic Messages 协议，不能直接复制。AI 应检查当前 CloudCLI / Claude Code 是否支持 OpenAI Chat 兼容模型，或者是否需要 adapter/router/agent.js 模式配置。

如果本地文档没有覆盖当前版本，必须联网检索，优先查官方文档、项目 README、release 和 issue。

## 需要测通的目标

配置完成后，至少确认：

- service-manager 可用。
- `/root/openhouse/scripts/check-ai-tools.sh` 已执行，并清楚说明缺失项。
- CloudCLI 服务已注册并运行。
- Claude Code 页面可访问。
- 默认账号密码说明清楚，仅限本机使用，后续可修改。
- Claude Code 能选择或使用迁移后的默认模型。
- 能完成一次最小模型请求。

不要只报告“配置已写入”。必须说明是否实际测通。

## 完成后介绍给用户

测通后，简要说明：

- `pi-agent` 是首次配置助手和文档索引员。
- 主工作台由用户选择，可以是 Claude Code、Codex、Hermes Web 或其它开源项目。
- `cc/codex` 是 CloudCLI / Claude Code / Codex 的统一入口。
- service-manager 是运行期控制平面。
- 终端一般不需要日常使用，高级排障时再进入。
- 内置浏览器可以打开本地服务。
- 文档目录在 `/root/openhouse/docs`。

最后给用户 `CLAUDE_CODE_HANDOFF.md` 中的复制提示词。

## 不要做什么

- 不要把用户锁定到 pi-agent。
- 不要把同一个 API key 的不同协议当成同一种配置。
- 不要在日志或聊天里回显 API key/token。
- 不要把未测通的配置说成可用。
- 不要在没有确认的情况下重装 Ubuntu、清理数据或覆盖用户项目。
