# /openhouse-first-config

全程使用中文进行回答，包括进度、错误、验证结果和最终交接说明；命令与接口字段保持原样。

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

一阶段 Agent 必须在模型配置、真实请求和第一阶段签名前亲自完成 AionUI 登录凭据标准化。默认账号固定为 `openhouse`，默认密码固定为 `openhouse123`。初始密码未知时，必须按以下顺序执行：停止 AionUI 服务；运行 `aionui-web resetpass --data-dir /root/.aionui-web` 得到临时用户名和随机密码；重启服务；向本机 AionUI（默认 `http://127.0.0.1:25808`）`POST /login` 并把临时会话 cookie 保存到权限受限的临时文件；携带该 cookie 向 `POST /api/webui/change-username` 发送 `{"new_username":"openhouse"}`；再向 `POST /api/webui/change-password` 发送 `{"new_password":"openhouse123"}`；清除临时会话并用 `openhouse / openhouse123` 重新登录、读取当前用户验证。使用 `curl` 和 `jq` 检查每一步 HTTP 状态与 JSON 响应。AionUI v2.1.32 已确认密码至少需要 8 位，因此 `123456` 过短；不要未经实测声称“常见密码”必然被拒绝。字段名必须是 `new_password`，不是 `newPassword`。随机密码、cookie 和会话令牌只能放在权限受限的临时文件或内存中，不得输出到日志、对话或交接文件，完成后立即清理。不得直接修改 AionUI 数据库；任一步失败都必须明确报告阻断，不得跳过或声称完成。

一阶段 Agent 还必须亲自读取自身当前实际生效的模型配置，并把同一份可用配置配置给用户选定的第二阶段 Agent，包括 provider/协议、Base URL、model id、必要参数以及 API key/token 本身或其安全引用。不得要求用户手工搬运普通配置；不得把密钥写入日志、对话或交接文件。配置后必须由一阶段 Agent 主动调用或驱动第二阶段 Agent 发起一次最小真实请求，并亲自核验得到有意义的有效响应；不能把这一步留给第二阶段 Agent 自行完成。凭据标准化、模型配置复制和第二阶段真实请求三项全部通过后，才允许完成第一阶段签名。

按 `OPENHOUSE_FIRST_CONFIGURATION.md` 和 `MODEL_API_SETUP.md` 配置并真实测通 AionUI。通过 `POST /api/providers` 创建 provider 时，`models` 必须是模型 ID 的字符串数组，例如 `"models":["deepseek-v4-pro"]`；不得传成 `[{"id":"deepseek-v4-pro","name":"..."}]` 对象数组。主示范模型 ID 使用精确的 `deepseek-v4-pro`；`deepseek-v4 pro` 中间带空格是错误 ID。`deepseek-v4-flash` 也已验证成功，但只作为另一可用模型的备注，不替换主示范。provider 创建成功但返回 `models: []` 不算配置成功。

模型配置完成后，一阶段 Agent 必须亲自按 `MODEL_API_SETUP.md` 的完整 AionUI CLI/HTTP 真实对话链执行：登录并保存 cookie；读取实际 assistant/agent 标识和 provider/model 配置；用精确的 `extra.sessionMode="default"` 创建一个新 AionRS conversation，并硬性确认 HTTP 状态码精确为 `201`；向该 conversation 发送唯一 nonce，用中文下达验证指令，并明确要求 assistant 最终文本只能原样回复该 nonce，同时硬性确认发送接口 HTTP 状态码精确为 `202`；轮询 conversation 直到 `status=finished`；最后读取消息历史响应的 `data.items`，并确认存在一个 assistant 文本精确等于该 nonce，不能只是包含 nonce 或表达对应语义。必须同时记录脱敏后的 conversation id、最终状态和 assistant 回复摘要作为验收证据。页面可打开、health-check、provider 保存成功、模型列表可见、接口只返回 2xx、conversation 创建成功或消息进入队列，都不能替代这条完整验证链。不得直接请求供应商 API 冒充 AionUI 测通，也不得让用户或第二阶段 Agent 自己补测。

AionUI 是第一阶段必须准备并真实测通的工作台；第二阶段 Agent 默认直接取 AionUI，identity 为 `aionui`，无需再次询问用户。只有用户已经明确指定其它第二阶段 Agent 时才覆盖此默认值。一阶段 Agent 必须亲自把自身实际生效的大模型配置配置给最终选定的第二阶段 Agent，并亲自驱动它完成上述同等级的中文 nonce 最小真实请求、轮询终态及 assistant 历史核验；最终目标是 AionUI 时就直接使用上述 AionUI 完整链，不重复做一套形式化检查。

只有用户已经明确指定其它第二阶段 Agent 时，才用该选择覆盖 AionUI 默认值；不要为了确认默认值再次询问用户。用户可以自由指定 Claude Code、Codex、Hermes 或其它 Agent，不把非默认选择称为高级或备用。确定最终目标后，准备最终实际选定的第二阶段 Agent 应用或工作台，并按对应文档完成真实验证。identity 应填写 `aionui`、`claude-code`、`codex`、`hermes` 等 Agent 名称，而不是模型名称。第二阶段 identity 必须与第一阶段不同，但可以使用相同模型。默认路径由一阶段 Agent 配置 AionUI 并主动驱动它完成最小真实请求；发生覆盖时，对最终实际目标执行相同的模型配置复制和主动最小真实请求流程。HANDOFF 和可复制话术始终使用最终实际选定的 Agent 名称与 identity。不要替第二阶段 Agent 做独立复核或签名。

第一阶段完成后，把脱敏交接材料写入 Termux 目录：

`$HOME/.local/share/openhouseai/handoffs/second-ai/latest`

目录必须包含：

- `HANDOFF.md`：第一阶段 identity、已完成事项、阻断项、第二阶段动作和验收标准；必须明确记录用户实际选定的第二阶段 Agent 名称和 identity。
- `system-check.json`：有效 JSON，至少包含 `schema`、`generatedAt`、`firstStageAgentIdentity`、`runtimeLayer`、`checks`、`warnings`、`secretsRedacted`；`secretsRedacted` 必须为 `true`。
- `task.json`：有效 JSON，至少包含 `schema`、`status`、`firstStageAgentIdentity`、`secondStageAgentIdentity`、`requireDifferentSecondStageIdentity`、`objective`、`requiredChecks`、`completionCriteria`；`secondStageAgentIdentity` 写入用户实际选择，初始 `status` 为 `ready_for_second_ai`，`requireDifferentSecondStageIdentity` 为 `true`。

在 `latest` 中先写同名临时文件，确认三个文件存在、两个 JSON 能解析且不含敏感信息后再用 `mv` 替换正式文件。目录权限设为 `700`，文件权限设为 `600`。如果当前位于 Ubuntu，使用 `openhouse-termux` / `oh-termux` 写入同一个 Termux 目录，不要在 `/root` 下建立第二套事实源。

签名前从 APK 版本化资源目录定位真实 bootstrap 并执行状态检查。Termux native 使用：

`resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo '未找到可用的 APK bootstrap 资源' >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh status`

Ubuntu 使用：

`openhouse-termux exec -- 'resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name "apk-*" | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh status'`

复核通过后，按 `OPENHOUSE_HEALTH_SIGNOFF.md` 使用当前真实 agent identity 完成第一阶段签名；确认状态要求第二阶段签名，而不是错误地报告全部完成。

必须根据用户实际选定的第二阶段 Agent 单独输出下面格式的可复制文本，并把完成实际值替换后的同一句写入 `HANDOFF.md`：

`请在 <实际选定的 Agent 名称> 中作为 OpenHouse 第二阶段 Agent（identity=<实际选定的 identity>），先阅读本机 OpenHouse 文档，再读取 Termux 的 $HOME/.local/share/openhouseai/handoffs/second-ai/latest（若你在 Ubuntu，请通过 openhouse-termux 访问）中的 HANDOFF.md、system-check.json 和 task.json，独立复核、完成任务，并仅在全部验收通过后完成第二阶段签名。`

输出前必须把 Agent 名称和 identity 替换为实际值，不得保留占位符，也不得擅自固定为 AionUI、Claude Code、Codex 或其它产品。

除本流程公开固定的 `openhouse` / `openhouse123` 外，不得在文档、交接文件、日志或回复中写出 API key、token、Authorization、cookie、密码、完整私有模型配置或带认证参数的 URL。
