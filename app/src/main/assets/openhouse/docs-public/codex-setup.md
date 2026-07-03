# Codex 配置

本文档说明 OpenHouse 中 Codex 的后置配置和验收要求。

Codex 是后置 AI 工作能力，不是首次安装主链路的阻塞项。首次安装应先保证 pi-agent、pi-web、service-manager、smallphone 和 cloudcli 的基本可用，再由 pi-agent 引导配置 Codex。

## 配置模式

Codex 至少可能有两种模式：

- 官方登录或 OAuth 模式。
- OpenAI-compatible API 模式。

具体采用哪种模式，应由用户提供的信息、当前 Codex 版本和官方文档决定。不要把 Claude Code 的配置格式直接套到 Codex 上。

## 用户需要提供

API 模式下，用户通常需要提供：

- `base_url`
- `key/token`
- `model id`
- 协议类型，如果知道

官方登录模式下，应按当前 Codex 官方流程完成登录，不把用户登录凭据写入文档或日志。

## 配置流程

1. 先阅读 `model-config-migration.md`。
2. 确认用户要使用官方登录还是 API 模式。
3. 检查 Codex 是否已经安装。
4. 如果缺失，由 pi-agent 调用 OpenHouse 脚本或官方安装方式。
5. 写入配置前先脱敏展示摘要。
6. 配置后执行一次最小可验证请求。
7. 失败时查 `troubleshooting.md`，必要时联网检索官方 README、release note 和 issue。

## 与 cc-switch 的关系

如果内置或安装了 `cc-switch`，可以用它辅助管理 Codex provider。cc-switch 是配置执行器，不是 Codex 本体，也不是长期服务。

## 验收标准

Codex 配置完成必须满足：

- Codex 命令或工作台入口可用。
- 目标 provider 和 model id 已写入对应配置。
- 完成一次真实请求并收到回复。
- 日志中没有完整 key/token。
- 如果通过 service-manager 托管相关 Web 服务，服务 ID 稳定且健康状态可查。

只检查配置文件存在不算完成。
