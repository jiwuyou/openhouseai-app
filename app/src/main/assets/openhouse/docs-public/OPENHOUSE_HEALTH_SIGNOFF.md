# OpenHouse 健康检查双阶段签名

本文给一阶段引导 AI、二阶段引导 AI 和用户共同参考。双阶段签名是引导阶段的健康复核，不是安装阶段编号。

## 目标

完整健康签名必须同时满足：

- 一阶段引导 AI 已完成签名。
- 二阶段引导 AI 已完成签名。
- 两个签名来自不同 AI 或不同 agent identity。
- 二阶段 AI 已独立阅读本页和相关健康检查文档。
- 签名记录只保存明文 agent 名，例如 `pi`、`claude-code`、`codex`、`openhouseai`、`hermes`，不保存 API key、token、Authorization、cookie、完整密钥或未脱敏日志。

只有一阶段签名时，状态必须保持未完成，并提示用户找第二个 AI 完成二阶段引导签名。

## 二阶段 AI 可以是谁

二阶段 AI 可以是：

- OpenHouseAI / AionUI / smallhouseai。
- Claude Code、Codex、CloudCLI 中的 `cc/codex`。
- pi 或 Hermes。
- 其它能读取本机状态并独立复核的 AI。

一阶段 AI 的责任不是代替二阶段 AI 签名，而是帮助用户让至少一个二阶段 AI 可用，并给用户可直接复制给二阶段 AI 的提示词。

## 二阶段 AI 必读文档

优先读取：

```text
/root/openhouse/docs/OPENHOUSE_HEALTH_SIGNOFF.md
/root/openhouse/docs/openhouse-install-flow.md
/root/openhouse/docs/service-manager.md
/root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md
```

如果 `/root/openhouse/docs` 不存在，使用：

```text
/root/openhouseai-docs/official/OPENHOUSE_HEALTH_SIGNOFF.md
/root/openhouseai-docs/official/openhouse-install-flow.md
/root/openhouseai-docs/official/service-manager.md
/root/openhouseai-docs/official/OPENHOUSE_FIRST_CONFIGURATION.md
```

## 二阶段复核步骤

在 bootstrap 所在目录运行：

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

二阶段 AI 独立复核通过后，在 bootstrap 所在目录执行：

```bash
bash bootstrap.sh sign-second-bootstrap-ai codex
```

可用的 agent 名示例：

```text
pi
claude-code
codex
openhouseai
hermes
```

然后再次运行：

```bash
bash bootstrap.sh status
```

确认完整签名状态为 true。若仍未通过，向用户报告缺项和下一步。

## 禁止事项

- 不要把一阶段签名当作完整签名通过。
- 不要使用与一阶段相同的 AI identity 完成二阶段签名。
- 不要把 API key、token、Authorization、cookie 或完整密钥写入签名记录、日志或回复。
- 不要为了健康签名重装系统、清理用户数据或覆盖用户项目。
