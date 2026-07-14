# OpenHouse 健康检查双阶段签名

本文给一阶段引导 AI、二阶段引导 AI 和用户共同参考。双阶段签名是引导阶段的健康复核，不是安装阶段编号。

## 目标

完整健康签名必须同时满足：

- 一阶段引导 AI 已完成签名。
- 二阶段引导 AI 已完成签名。
- 两个签名来自不同 AI 或不同 agent identity。
- 二阶段 AI 已独立阅读本页和相关健康检查文档。
- 签名记录只保存实际、稳定、可比较的 agent identity，不保存 API key、token、Authorization、cookie、密码、完整密钥、私有模型配置或未脱敏日志。

只有一阶段签名时，状态必须保持未完成，并提示用户找第二个 AI 完成二阶段引导签名。

## 二阶段 AI 可以是谁

二阶段 AI 可以运行在：

- OpenHouseAI / AionUI / smallhouseai。
- Claude Code、Codex、CloudCLI 中的 `cc/codex`。
- pi 或 Hermes。
- 其它能读取本机状态并独立复核的 AI。

这些只是可选入口，不是固定身份。一阶段 AI 的责任不是代替二阶段 AI 签名，而是帮助用户让至少一个不同 identity 的二阶段 AI 可用，并按 `SECOND_AI_HANDOFF.md` 生成三个交接文件和一句可复制提示词。

pi-web 只是推荐的第一阶段入口，AionUI 只是固定安装后可选的第二阶段工作台之一。两阶段 AI 都不绑定具体产品或模型；签名必须记录实际 agent identity，并保持两者不同。交接文件按 `SECOND_AI_HANDOFF.md` 保存。

## 第一阶段签名命令

bootstrap 位于最新的 APK 版本化资源目录。第一阶段 AI 独立复核通过后，在 Termux native 执行：

```bash
resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1)
[ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }
cd "$resource_dir/bootstrap"
bash bootstrap.sh sign-first-bootstrap-ai '<实际第一阶段identity>'
bash bootstrap.sh status
```

当前在 Ubuntu 时执行：

```bash
openhouse-termux exec -- 'resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name "apk-*" | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh sign-first-bootstrap-ai "<实际第一阶段identity>"'
openhouse-termux exec -- 'resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name "apk-*" | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh status'
```

第一阶段 AI 只能写入自己的真实 identity，不能预先替第二阶段签名。

## 二阶段 AI 必读文档

优先读取：

```text
/root/openhouse/docs/OPENHOUSE_HEALTH_SIGNOFF.md
/root/openhouse/docs/openhouse-install-flow.md
/root/openhouse/docs/SERVICE_MANAGER.md
/root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md
/root/openhouse/docs/SECOND_AI_HANDOFF.md
```

如果 `/root/openhouse/docs` 不存在，使用：

```text
/root/openhouseai-docs/official/OPENHOUSE_HEALTH_SIGNOFF.md
/root/openhouseai-docs/official/openhouse-install-flow.md
/root/openhouseai-docs/official/SERVICE_MANAGER.md
/root/openhouseai-docs/official/OPENHOUSE_FIRST_CONFIGURATION.md
/root/openhouseai-docs/official/SECOND_AI_HANDOFF.md
```

## 二阶段复核步骤

按上面的运行层规则，在最新资源目录的 `bootstrap` 中运行：

```bash
bash bootstrap.sh status
```

重点查看：

- 运行栈 readiness。
- `healthCheck`、`healthSignatures` 或 `bootstrapAiSignatures`。
- `fullHealthCheckDue` 或 `fullHealthCheck.due`。
- 一阶段签名 signer。
- 二阶段签名是否缺失、无效或与一阶段 signer 相同。

如果需要刷新全面健康检查时间，运行：

```bash
bash bootstrap.sh check
```

默认全面健康检查提醒间隔是 7 天。可用下面命令修改：

```bash
bash bootstrap.sh set-full-health-check-interval 7
```

## 二阶段签名命令

二阶段 AI 独立复核通过后，在 Termux native 执行：

```bash
resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1)
[ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }
cd "$resource_dir/bootstrap"
bash bootstrap.sh sign-second-bootstrap-ai '<实际第二阶段identity>'
bash bootstrap.sh status
```

当前在 Ubuntu 时执行：

```bash
openhouse-termux exec -- 'resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name "apk-*" | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh sign-second-bootstrap-ai "<实际第二阶段identity>"'
openhouse-termux exec -- 'resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name "apk-*" | sort | tail -n 1); [ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }; cd "$resource_dir/bootstrap" && bash bootstrap.sh status'
```

签名后还必须把 Termux 交接目录中的 `task.json.status` 更新为 `completed`，写入 `secondStageAgentIdentity`、`completedAt` 和脱敏的 `resultSummary`。

identity 不能只写 `first-ai`、`second-ai`、`assistant` 等角色名，也不能虚构产品名。建议使用工作台或 agent 名加当前模型/配置别名形成稳定字符串，但不得包含密钥。bootstrap 按字符串比较两个 signer；两者相同不会形成完整签名。

最后再次确认：

```bash
bash bootstrap.sh status
```

确认完整签名状态为 true。若仍未通过，向用户报告缺项和下一步。

## 禁止事项

- 不要把一阶段签名当作完整签名通过。
- 不要使用与一阶段相同的 AI identity 完成二阶段签名。
- 不要把 API key、token、Authorization、cookie 或完整密钥写入签名记录、日志或回复。
- 不要为了健康签名重装系统、清理用户数据或覆盖用户项目。
- 不要在第二阶段独立复核和任务验收之前签名。
