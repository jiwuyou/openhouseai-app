# 首次 OpenHouse 配置

本文说明：固定安装已经完成后，如何让用户选择的第一阶段 Agent 完成第一次系统检查、准备另一个独立 Agent，并把任务交给第二阶段 Agent 接力。这里的第一阶段和第二阶段指两个 Agent 应用或工作台，不是两个大模型；两个 Agent 可以使用相同模型。第一阶段 Agent 不固定具体产品；第二阶段 Agent 默认直接使用 AionUI，无需再次询问，只有用户明确指定其它 identity 不同的 Agent 时才覆盖默认选择，使用的模型不固定。

目标不是让用户手工搬运上下文，而是让第一阶段 Agent 读取文档、检查系统、准备一个身份不同的第二阶段 Agent，并把脱敏任务交给它继续完成。第一阶段 Agent 全程使用中文回答。当前内测固定要求第一阶段准备并真实测通 AionUI，默认也直接由 AionUI 承担第二阶段；只有用户明确指定时才改用其它 identity 不同的 Agent，最终由第一阶段 Agent 配置并驱动实际选定的 Agent 完成最小真实请求。

## 触发条件

用户打开 OpenHouse 适配版 pi-web 后，点击页面内的“OpenHouse 首次配置”入口。pi-web 在内部运行标准模板：

```text
/openhouse-first-config
```

pi-web 的 OpenHouse 适配分支只负责显示入口、创建会话并调用模板名，不内置文档地址和任务正文；通过 pi-web 执行任务时，`pi-web` 可以作为当前 Agent identity。完整模板由最终 APK payload 安装到 `$PI_CODING_AGENT_DIR/prompts`；实机默认文件是 `$HOME/.pi/prompts/openhouse-first-config.md`。Android 不传 prompt URL，也不理解 AI 会话协议。

执行前应确认：

- OpenHouse 固定安装已完成，官方文档和 service-manager 可用。
- 已存在可用模型配置，并且至少一个模型已经测试成功。
- OpenHouse 首次配置尚未完成。

界面应提示：

```text
首次使用 OpenHouse，看到我，请点击我，完成首次配置。
```

用户点击后，pi-web 应在 `PI_WEB_DEFAULT_CWD` 指定的默认工作目录开启新会话并运行模板。也可以在新会话中手动运行 `/openhouse-first-config`。不要假设第一阶段 AI 是 pi-agent，也不要假设当前命令层一定是 Ubuntu `/root`。

## 内测默认线

1. 第一阶段 Agent 检查固定安装、文档、service-manager、Ubuntu 和运行服务。
2. 第一阶段 Agent 按 `MODEL_API_SETUP.md` 把未知的 AionUI 管理员凭据标准化为用户名 `openhouse`、密码 `openhouse123`，再使用 `openhouse / openhouse123` 登录验证。`resetpass` 会先生成随机临时密码，旧 session 失效属于预期。
3. 登录验证通过后，第一阶段 Agent 才在 AionUI 保存模型配置，并用 AionUI CLI 创建对话、发送消息、等待完成、核验 assistant 回复。
4. 第二阶段 Agent 默认直接使用 AionUI，无需再次询问；只有用户明确指定其它 identity 不同的 Agent 时才覆盖。第一阶段 Agent 亲自把自己当前有效的大模型配置配置给实际选定的 Agent，并主动调用或驱动它完成一次最小真实请求。凭据只写入第二阶段 Agent 的本机配置，不写入聊天、日志或交接文件。
5. 第二阶段 Agent 的最小真实请求通过后，第一阶段 Agent 才生成交接文件并完成第一阶段签名。
6. 第二阶段 Agent 独立复核并完成第二阶段签名；默认直接使用 AionUI，只有用户明确指定其它 Agent 时才使用该 Agent 应用或工作台，模型不固定。
7. `task.json.status` 更新为 `completed` 后，首次配置完成。
8. 完成后提供四个可选任务：配置 Claude Code、配置 Codex、创建小型 Web App、跳过。

这四个任务都不阻断首次配置完成。AionUI 账号重置、登录验证和模型实测是一阶段固定动作；AionUI 是无需再次询问的默认第二阶段 Agent，只有用户明确指定时才改用其它 identity 不同的 Agent。

## 第一阶段 AI 要做什么

第一阶段 AI 应按顺序执行：

1. 阅读 OpenHouse 文档索引。
2. 确认当前文档路径。
3. 确认当前 Agent 应用或工作台及其模型配置；identity 使用 `pi-web`、`codex`、`claude-code`、`aionui` 等 Agent 名称，不使用 GPT、Claude、DeepSeek 等模型名称。
4. 在任何模型配置、真实请求和第一阶段完成签名前，按 `MODEL_API_SETUP.md` 停止 AionUI、运行 `aionui-web resetpass --data-dir /root/.aionui-web`，再通过 HTTP API 把账号标准化为 `openhouse / openhouse123` 并重新登录验证；旧 session 失效属于预期。
5. 登录验证通过后读取并配置 AionUI，按 `MODEL_API_SETUP.md` 核对协议和字段。
6. 可记录后置 AI 工具状态，但缺少 Claude Code、Codex、CloudCLI 或其它可选工具不阻断默认线。
7. 默认线完成前不安装这些可选工具；用户在双签后选择相应任务时，再执行对应后置安装脚本。
8. 判断 provider、baseUrl、modelId、协议和凭据来源。
9. 先把可用配置写入 AionUI 并保存；CloudCLI、Claude Code、Codex 或其它目标配置放到首次配置完成后的可选任务。
10. 遇到协议差异时，按文档和网络检索确认正确配置。
11. 启动或重启相关服务。
12. 确认 AionUI 可打开，模型配置已保存，协议、Base URL 和 model id 可用；随后必须按 `MODEL_API_SETUP.md` 通过 CLI 创建 AionRS 对话，并确认目标 assistant 的最终文本精确等于唯一校验串。页面、`/health`、provider 列表或健康检查都不能替代这次真实消息。
13. 默认直接使用 AionUI 作为 identity 不同的第二阶段 Agent，无需再次询问；只有用户明确指定其它 Agent 时才覆盖，模型不固定。
14. 第一阶段 Agent 亲自把自己当前正在使用且已验证有效的大模型配置配置给第二阶段 Agent。必须按目标 Agent 的协议和字段正确迁移，凭据只落入目标的本机配置，不得写入聊天、日志、截图、交接文本或签名证据。
15. 第一阶段 Agent 主动调用或驱动第二阶段 Agent 发出一次最小真实请求并核验有效响应；只启动服务或写入配置不算通过。
16. 上一步通过后，向用户介绍基本入口和下一步选择，生成第二 AI 交接文件，引用 `SECOND_AI_HANDOFF.md`，完成第一阶段签名。
17. 给用户一句可直接复制给用户所选第二阶段 Agent 的话，并引导它完成第二阶段独立复核与签名。
18. 双签和任务完成后，提供配置 Claude Code、配置 Codex、创建小型 Web App、跳过四个非阻断选项。

## 首先阅读的文档

Termux native 第一阶段 AI 的优先路径：

```text
/data/data/com.termux/files/home/openhouse/docs
```

Ubuntu 工作台中 AI 的等价路径是 `/root/openhouse/docs`。两个路径指向同一份公开文档。

必读文档：

```text
/root/openhouse/docs/START_HERE.md
/root/openhouse/docs/CAPABILITIES_MAP.md
/root/openhouse/docs/WORKBENCH_OPTIONS.md
/root/openhouse/docs/AI_TOOL_POSTINSTALL.md
/root/openhouse/docs/MODEL_API_SETUP.md
/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md
/root/openhouse/docs/cc-switch.md
/root/openhouse/docs/SERVICE_MANAGER.md
/root/openhouse/docs/GITHUB_NETWORK_MIRRORS.md
/root/openhouse/docs/CLAUDE_CODE_HANDOFF.md
/root/openhouse/docs/SECOND_AI_HANDOFF.md
```

如果 `/root/openhouse/docs` 不存在，改用：

```text
/root/openhouseai-docs/official
```

## 内测默认 AionUI 配置

当前内测把 OpenHouseAI / AionUI 的账号准备和模型实测列为第一阶段固定动作，并默认直接使用 AionUI 作为第二阶段 Agent，无需再次询问；只有用户明确指定其它 identity 不同的 Agent 时才覆盖。当前内测默认管理员凭据是：

```text
用户名：openhouse
密码：openhouse123
```

第一阶段必须实际完成账号标准化，并在模型配置前使用 `openhouse / openhouse123` 登录验证。AionUI 初始密码可能未知，应先由 service-manager 停止服务，再运行 `aionui-web resetpass --data-dir /root/.aionui-web` 获得随机临时密码，重启后用 session cookie 调用 `change-username` 和 `change-password`。完整命令只维护在 `MODEL_API_SETUP.md`。

应指导用户在 OpenHouseAI / AionUi 的模型配置页面确认：

- “模型平台”选择正确；优先使用 AionUi 内置平台。
- “平台名称 / 模型供应商”能让用户识别这组配置。
- `base url` 是服务根地址；普通模式下 AionUi 会自动拼接请求路径。
- 只有供应商要求直接请求完整接口地址时，才使用“完整URL”。
- `API Key` 只写入本机配置，不显示在聊天、日志、截图或文档中。
- 已使用 AionUi 自带协议检测确认协议，或按检测建议切换了模型平台。
- 已使用 AionUi 拉取模型列表；如果无法拉取，已确认手动输入的模型 ID 来自供应商文档。
- 已使用“测试密钥 / 测试所有密钥”确认 Key 可用。
- “模型名称 / 模型 ID”是供应商真实模型 ID。
- 如果使用 New API 网关，每个模型的“请求协议”已按上游设置为 OpenAI、Gemini 或 Anthropic。
- 模型健康检查已经通过，`model_health.status` 为 healthy，并已保存为默认模型；这仍不能替代下面的真实消息验证。

只有同时确认 AionUI 可打开、模型配置已保存、协议/Base URL/model id 可用，并按 `MODEL_API_SETUP.md` 通过登录 cookie、创建对话、发送消息、轮询到 `finished`、读取 assistant 文本这条完整链路，才能标记为真实测通。`models` 必须是字符串数组；主示范模型 ID 是 `deepseek-v4-pro`，不能误写成带空格的 `deepseek-v4 pro`。高级或备用路径使用其它工作台时，也必须完成等价的真实请求。

## 模型配置迁移原则

AionUi 或 pi-web 的模型配置不能机械复制到 Claude Code。AI 必须理解协议差异。

需要识别：

- AionUi `platform`、`name`、`base_url`、`models[]`、`model_protocols`。
- AionUi 协议检测、模型列表拉取、Key 测试和 `model_health` 结果。
- pi-web provider 名称和别名。
- base URL 是否包含 `/v1`、`/chat/completions`、`/responses`、`/messages` 等路径。
- API 协议类型，例如 OpenAI Chat、OpenAI Responses、Anthropic Messages、Gemini Native。
- modelId 是否适合目标工具。
- 凭据是 API key、token、header 还是环境变量。
- CloudCLI / Claude Code 当前版本支持哪种配置格式。

## DeepSeek 示例

DeepSeek 经常使用同一个密钥，但不同工具可能要求不同协议。

常见 AionUi 或 pi-web 配置可能是：

```text
platform/name: deepseek
base_url/baseUrl: https://api.deepseek.com/v1
model/modelId: deepseek-chat
```

这适合 OpenAI Chat 兼容接口。若 Claude Code 或 CloudCLI 侧期望 Anthropic Messages 协议，不能直接复制。AI 应检查当前 CloudCLI / Claude Code 是否支持 OpenAI Chat 兼容模型，或者是否需要 adapter/router/agent.js 模式配置。

如果本地文档没有覆盖当前版本，必须联网检索，优先查官方文档、项目 README、release 和 issue。

## 需要测通的目标

配置完成后，至少确认：

- OpenHouse 文档可读。
- service-manager 可用。
- pi/pi-web 与 Ubuntu 的真实状态已检查。
- 后置 AI 工具检查已执行，并清楚区分阻断项和可选缺失项。
- AionUI 管理员账号已实际标准化为 `openhouse / openhouse123`，已有会话失效后已使用该凭据重新登录成功。
- AionUI 可打开，模型配置已保存，协议、Base URL 和 model id 可用。
- AionUI CLI 已创建真实对话、发送中文最小请求、轮询到 `finished`，并确认目标 assistant 的最终文本精确等于唯一校验串。
- 第一阶段 Agent 已亲自把自己当前有效的大模型配置配置给所选第二阶段 Agent，且凭据没有进入聊天、日志或交接文件。
- 第一阶段 Agent 已主动调用或驱动第二阶段 Agent 完成一次最小真实请求并核验有效响应；此后才完成第一阶段签名。
- 用户选择的第二阶段 Agent 已完成独立复核，且与第一阶段 Agent identity 不同。
- 若用户选择 CloudCLI、Claude Code、Codex、AionUI 或其它工作台，其对应配置与服务已按该工作台文档验证。

不要只报告“配置已写入”。必须说明是否实际测通。

## 完成后介绍给用户

测通后，简要说明：

- pi-web 是推荐的首次配置入口，通过它执行任务时可以把 `pi-web` 作为 Agent identity；`pi-agent` 是可选运行时和文档索引员。
- 主工作台由用户选择，可以是 Claude Code、Codex、Hermes Web 或其它开源项目。
- `cc/codex` 是 CloudCLI / Claude Code / Codex 的统一入口。
- service-manager 是运行期控制平面。
- 终端一般不需要日常使用，高级排障时再进入。
- 内置浏览器可以打开本地服务。
- Termux 文档目录是 `$HOME/openhouse/docs`，Ubuntu 文档目录是 `/root/openhouse/docs`。

最后生成 `SECOND_AI_HANDOFF.md` 规定的三个本地交接文件，验证两个 JSON 可解析，完成第一阶段签名，给用户可复制的接力提示词，并让用户所选第二阶段 Agent 完成独立复核与签名。完成后提供以下四个选项：

- 配置 Claude Code：阅读 `CLOUDCLI_CLAUDE_CODE.md` 和 `MODEL_API_SETUP.md`。
- 配置 Codex：阅读 `codex-setup.md` 和 `MODEL_API_SETUP.md`。
- 创建小型 Web App：阅读 `CUSTOM_FRONTEND_AND_APPS.md`。
- 跳过：直接结束首次配置。

这些选项不影响首次配置的完成状态。需要更换第二阶段 Agent 时，仍可使用 Claude Code、Codex、Hermes、另一个 pi 会话或其它 AI 走高级/备用路径。

## 内测通过标准

一次完整内测必须看到：

1. 第一阶段 AI 真实检查系统并确认自己当前使用的大模型配置有效。
2. Termux 唯一交接目录中存在 `HANDOFF.md`、`system-check.json`、`task.json`，两个 JSON 可解析且没有密钥。
3. AionUI 管理员账号已实际标准化为 `openhouse / openhouse123`，并在旧会话失效后使用该凭据重新登录成功；该动作发生在模型配置、真实请求和第一阶段签名前。
4. 第一阶段 Agent 已亲自把自己当前有效的大模型配置配置给 identity 不同的第二阶段 Agent，且密钥没有出现在聊天、日志、交接文件或签名证据中。
5. 第一阶段 Agent 已主动调用或驱动第二阶段 Agent 完成一次最小真实请求并核验有效响应；随后才生成第一阶段签名，bootstrap 状态明确等待第二阶段签名。
6. 用户获得一句可直接复制的话，并在所选第二阶段 Agent 中成功开始接力。
7. 第二阶段 Agent 独立复核、完成任务、完成第二阶段签名，并把 `task.json.status` 更新为 `completed`。
8. 最终 bootstrap 状态显示双阶段签名完整且 signer 不同。
9. 完成后显示四个可选任务；用户选择“跳过”也保持首次配置完成。

## 不要做什么

- 不要把用户锁定到 pi-agent。
- 不要把同一个 API key 的不同协议当成同一种配置。
- 不要在日志或聊天里回显 API key/token。
- 不要把未测通的配置说成可用。
- 不要在没有确认的情况下重装 Ubuntu、清理数据或覆盖用户项目。
