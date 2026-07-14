# 第二 AI 接力

OpenHouse 的首次配置由两个 Agent 应用或工作台接力完成。第一阶段 Agent 检查系统、准备可用的第二阶段入口并生成脱敏任务；第二阶段 Agent 独立复核、继续完成任务并完成第二阶段签名。第一阶段和第二阶段不是指两个大模型，两个 Agent 可以使用相同模型。

pi-web 是推荐的第一阶段 Agent 工作台，通过它执行任务时可以使用 `pi-web` 作为 Agent identity。OpenHouse 适配分支在页面内调用 `/openhouse-first-config` 或 `/openhouse-second-ai-handoff`；完整任务、文档地址和交接规则由最终 APK payload 中的标准 prompt 提供，Android 不注入 prompt URL。

当前内测默认由 pi-web 完成第一阶段，再由已真实测通的 AionUI 完成第二阶段独立复核与签名。其它 Agent 组合保留为高级或备用路径。

## 唯一交接目录

交接目录只保存在 Termux native：

```text
/data/data/com.termux/files/home/.local/share/openhouseai/handoffs/second-ai/latest
```

在 Termux 中可写成：

```text
$HOME/.local/share/openhouseai/handoffs/second-ai/latest
```

Ubuntu 工作台不得在 `/root` 下建立第二套交接目录，应使用 `openhouse-termux` / `oh-termux` 访问 Termux 中的唯一事实源。

## 第一阶段 AI 的交付物

第一阶段 AI 必须生成三个文件：

```text
latest/
├── HANDOFF.md
├── system-check.json
└── task.json
```

目录权限应为 `700`，文件权限应为 `600`。写入时先使用同目录临时文件，验证后再用 `mv` 替换正式文件。

### `HANDOFF.md`

至少记录：

- 第一阶段真实 agent identity 和生成时间。
- 当前运行层、文档路径和交接目录。
- 已完成检查及其简短证据。
- 非阻断警告和仍需第二阶段处理的任务。
- 第二阶段必须独立执行的检查与验收标准。
- 第一阶段签名结果。
- 一句可直接复制给第二阶段 AI 的接力话术。

不得包含 API key、token、Authorization、cookie、密码、完整私有模型配置、带认证参数的 URL 或未脱敏日志。

### `system-check.json`

最小结构：

```json
{
  "schema": 1,
  "generatedAt": "2026-07-14T00:00:00Z",
  "firstStageAgentIdentity": "actual-agent-identity",
  "runtimeLayer": "termux",
  "checks": {
    "docs": { "status": "pass", "evidence": "START_HERE.md readable" },
    "serviceManager": { "status": "pass", "evidence": "health endpoint responded" },
    "piWeb": { "status": "pass", "evidence": "local page responded" },
    "ubuntu": { "status": "pass", "evidence": "proot login and id succeeded" },
    "secondAi": { "status": "pass", "evidence": "AionUI opened and returned a valid model response" }
  },
  "warnings": [],
  "secretsRedacted": true
}
```

`status` 使用 `pass`、`warn` 或 `fail`。证据只保存可分享摘要，不复制完整配置或日志。示例 identity 只是字段格式，不代表固定产品。

### `task.json`

最小结构：

```json
{
  "schema": 1,
  "status": "ready_for_second_ai",
  "firstStageAgentIdentity": "actual-agent-identity",
  "requireDifferentSecondStageIdentity": true,
  "objective": "独立复核 OpenHouse 并完成当前用户任务",
  "requiredChecks": [
    "读取指定 OpenHouse 文档",
    "独立检查核心服务",
    "完成一次最小真实模型请求",
    "完成 HANDOFF.md 中的当前任务"
  ],
  "completionCriteria": [
    "所有阻断项已解决或明确报告",
    "第二阶段 identity 与第一阶段不同",
    "第二阶段签名成功",
    "bootstrap status 显示双签名完整"
  ]
}
```

第二阶段完成后把 `status` 改为 `completed`，并增加：

```json
{
  "secondStageAgentIdentity": "actual-second-agent-identity",
  "completedAt": "2026-07-14T00:00:00Z",
  "resultSummary": "不含敏感信息的结果摘要"
}
```

## 第一阶段执行顺序

1. 阅读 `START_HERE.md`、`OPENHOUSE_FIRST_CONFIGURATION.md`、`OPENHOUSE_HEALTH_SIGNOFF.md` 和 `SERVICE_MANAGER.md`。
2. 确认固定安装、文档、service-manager、pi/pi-web、Ubuntu 和已安装工作台的真实状态。
3. 默认配置并测通 AionUI：确认页面可打开、模型配置已保存、协议/Base URL/model id 可用，并且健康检查通过或真实对话收到有效响应。用户明确需要其它工作台时，才走高级或备用路径。
4. 生成并验证三个脱敏交接文件。
5. 使用自己的真实 identity 完成第一阶段签名。
6. 再次检查 bootstrap 状态，确认当前仍在等待第二阶段签名。
7. 默认把下面的一句话交给用户，并替换其中的 `<firstStageAgentIdentity>`；高级或备用路径再把 AionUI 替换为实际工作台：

```text
请在 AionUI 中作为与第一阶段 Agent（<firstStageAgentIdentity>）不同的 OpenHouse 第二阶段 Agent，先阅读本机 OpenHouse 文档，再读取 Termux 的 $HOME/.local/share/openhouseai/handoffs/second-ai/latest（若你在 Ubuntu，请通过 openhouse-termux 访问）中的 HANDOFF.md、system-check.json 和 task.json，独立复核、完成任务，并仅在全部验收通过后以你的真实 identity 完成第二阶段签名。
```

## 第二阶段执行顺序

1. 读取三个交接文件，确认两个 JSON 可解析、`secretsRedacted` 为 `true`、任务状态为 `ready_for_second_ai`。
2. 对比 `firstStageAgentIdentity` 与自己的真实 identity；相同则停止，请用户换一个 AI。
3. 独立阅读 OpenHouse 文档并重做关键检查，不能只复述第一阶段结论。
4. 完成 `requiredChecks`、当前任务和 `completionCriteria`。
5. 只有全部验收通过后，使用自己的真实 identity 完成第二阶段签名。
6. 将 `task.json.status` 更新为 `completed`，写入第二阶段 identity、完成时间和脱敏摘要。
7. 再次检查 bootstrap 状态，确认双阶段 signer 均存在且不同。

## 完成后的可选任务

第二阶段签名完成且 `task.json.status` 已更新为 `completed` 后，首次配置已经完成。此时提供四个非阻断选项：

- 配置 Claude Code：阅读 `CLOUDCLI_CLAUDE_CODE.md` 和 `MODEL_API_SETUP.md`。
- 配置 Codex：阅读 `codex-setup.md` 和 `MODEL_API_SETUP.md`。
- 创建小型 Web App：阅读 `CUSTOM_FRONTEND_AND_APPS.md`。
- 跳过：不执行额外任务，保持完成状态。

## 真实签名命令

bootstrap 位于最新的 APK 版本化资源目录。每次执行前都重新选择最新 `apk-*` 目录并验证脚本存在，不复用旧目录。

当前在 Termux native：

```bash
resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1)
[ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }
cd "$resource_dir/bootstrap"
bash bootstrap.sh sign-first-bootstrap-ai '<实际第一阶段identity>'
bash bootstrap.sh status

# 仅由第二阶段 AI 独立复核通过后执行：
bash bootstrap.sh sign-second-bootstrap-ai '<实际第二阶段identity>'
bash bootstrap.sh status
```

当前在 Ubuntu：

```bash
openhouse-termux exec -- 'resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name "apk-*" | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh status'
openhouse-termux exec -- 'resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name "apk-*" | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh sign-first-bootstrap-ai "<实际第一阶段identity>"'

# 仅由第二阶段 AI 独立复核通过后执行：
openhouse-termux exec -- 'resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name "apk-*" | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh sign-second-bootstrap-ai "<实际第二阶段identity>"'
openhouse-termux exec -- 'resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name "apk-*" | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh status'
```

identity 应使用真实、稳定、可比较的 Agent 应用或工作台名称，例如 `pi-web`、`codex`、`claude-code`、`aionui`；不能写成 `first-ai`、`second-ai`、`assistant` 等阶段角色名，也不要填写 GPT、Claude、DeepSeek 等模型名称。签名命令会按字符串比较两阶段 signer；两者相同不会形成完整签名。

## 失败处理

- 缺少某个可选工具：记录为 `warn`，只要不影响当前接力即可继续。
- 固定安装、文档、service-manager 或第二阶段 AI 不可用：记录为 `fail`，先修复再交接。
- 交接文件包含敏感信息：停止接力，删除敏感副本并重新生成脱敏文件。
- 第二阶段不能直接访问手机文件：用户只复制 `HANDOFF.md` 和上述接力话术；涉及状态检查和签名时，仍需让能访问本机的 AI 或用户在本机执行。
- 第二阶段复核失败：不得签名，保留 `ready_for_second_ai` 并明确下一步。
