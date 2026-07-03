# 实现验收清单

本文档是下一轮代码实现的放行标准。任何实现都应围绕“产品彻底可用”验证，而不是只验证某个脚本执行成功。

最高验收目标：

```text
新用户从安装 APK 到成功使用 Claude 或 Codex 收到回复，全程不需要理解 Termux、Ubuntu、Node 或 service-manager。
```

## 范围边界

下一轮实现必须覆盖：

- 首次安装状态机。
- 常规重试。
- 国内网络重试。
- 前台自动保持核心服务运行。
- 全部退出。
- 首次教学入口和脚本。
- 终端教学独立入口。
- pi-agent 首次配置助手。
- Claude 或 Codex 至少一个真实可用。
- 安全日志和脱敏。

不应把这些工作混成一个无法验收的大改动。每个模块必须有独立验证。

## 文档验收

实现前，必须确认这些文档存在并可读：

- `openhouse-overview.md`
- `openhouse-install-flow.md`
- `openhouse-cn-network-retry.md`
- `first-use-tutorial.md`
- `pi-agent-first-use.md`
- `model-config-migration.md`
- `cloudcli-claude-code-setup.md`
- `codex-setup.md`
- `service-manager.md`
- `openhouse-runtime-policy.md`
- `openhouse-exit-all.md`
- `troubleshooting.md`
- `ai-reference-index.md`
- `implementation-acceptance-checklist.md`

文档必须同步到运行期：

```text
/root/openhouse/docs
```

## 安装状态机验收

每个安装阶段必须支持：

- `pending`
- `running`
- `succeeded`
- `failed`
- `skipped`
- `retrying`

每个阶段必须定义并实现：

- 成功条件。
- 失败条件。
- 超时时间。
- 日志位置。
- 常规重试行为。
- 国内网络重试行为。
- 强制重试清理范围。
- 不允许清理的数据范围。

成功条件不能只依赖文件存在。关键阶段必须使用至少一种实际验证：

- 版本检查。
- sha256 校验。
- 可执行命令检查。
- 端口检查。
- API 健康检查。
- service-manager 服务状态。

## 重试验收

用户必须能看到两个主要重试入口：

- 常规重试。
- 国内网络重试。

常规重试必须：

- 复用已有缓存。
- 从失败阶段继续。
- 不删除用户数据。
- 不删除模型配置。

国内网络重试必须：

- 使用固定国内路径。
- 不让用户选择多个源。
- 固定 npm registry、GitHub 镜像、二进制包源和 payload fallback。
- 对所有下载内容做 sha256 校验。
- 失败时给出明确阶段和下一步，不停在空白页面。

强制重试必须：

- 清理当前阶段 marker。
- 清理当前阶段临时产物。
- 不删除用户文件、模型配置、日志、已完成 payload、用户项目目录。

## 前台运行验收

App 前台时，默认核心服务必须可用：

- `service-manager`
- `smallphone`
- `pi-agent`
- `cloudcli`

行为要求：

- Android 侧每 15-30 秒轻量检测 service-manager。
- 连续 2 次失败触发修复。
- 连续 3 次失败提示用户。
- service-manager 存活后检查核心服务。
- 服务拉起必须节流，避免 CPU 拉满。
- UI 状态、service-manager 状态和端口健康必须一致。

新增长期服务默认进入 service-manager 托管，除非显式标记为手动服务。

## 全部退出验收

点击“全部退出”后，必须停止：

- service-manager 管理的长期服务。
- service-manager 本身。
- OpenHouse 拉起的 Termux 长期进程。
- OpenHouse 拉起的 Ubuntu 长期进程。
- `smallphone`
- `pi-agent`
- `cloudcli`
- `openhouse-connect`

必须保留：

- 用户文件。
- 用户项目。
- 模型配置。
- 日志。
- 已安装 payload。
- service-manager 配置。
- pi-agent / pi-web 数据。
- Claude / Codex 配置。

退出后 UI 应显示未运行。再次打开 App 后，应重新启动 service-manager，并恢复默认长期服务。

## 首次教学验收

首次教学必须：

- 不进入终端教学。
- 说明菜单位置。
- 说明 pi-agent 是配置助手。
- 说明 cc/codex 是主要 AI 工具入口。
- 说明核心服务会前台自动运行。
- 说明可以从菜单或设置重新打开使用教学。
- 说明需要时可单独查看终端教学。

每一步教学必须明确：

- 界面。
- 箭头指向。
- 点击主体：用户点击、系统模拟或只点下一步。
- 是否允许跳过。
- 20 秒跳过规则。
- 完成条件。

需要用户真实点击的动作，首次 20 秒内不允许跳过。不需要点击的动作只显示“下一步”。

## pi-agent 和模型配置验收

pi-agent 必须被表达为：

- 首次配置助手。
- 文档索引员。
- 模型配置迁移助手。
- Claude/Codex/CloudCLI 配置助手。

pi-agent 不能被表达为唯一主工作台。

模型配置必须覆盖：

- `base_url`
- `key` 或 `token`
- `model id`
- 协议类型

协议类型至少区分：

- OpenAI-compatible
- Anthropic-compatible
- Claude 官方
- OpenAI/Codex 官方
- 中转协议

配置完成后必须实测。DeepSeek 等 provider 不得只按品牌判断协议。

## Claude / Codex E2E 验收

真机必须走通：

1. 干净安装 APK。
2. 完成首次安装。
3. pi-web 可打开。
4. pi-agent 可打开。
5. 用户配置模型。
6. Claude 或 Codex 至少一个真实发送消息并收到回复。
7. CloudCLI `23083` 可访问。
8. service-manager 服务状态正确。
9. UI 状态、service-manager 状态、端口健康一致。
10. 点击全部退出后核心端口不可达。
11. 重新打开 App 后核心服务自动恢复。

## 真机测试矩阵

必须覆盖：

- 干净安装。
- 安装中断后常规重试。
- 安装中断后国内网络重试。
- Node 阶段失败。
- payload sha 不一致。
- service-manager 未启动。
- 服务重复注册。
- CloudCLI 端口错误。
- pi-agent 不可达。
- pi-web WebView 打开异常。
- 全部退出。
- 全部退出后重新打开。
- 模型配置错误。
- Claude/Codex 实测失败。

## 安全验收

不得泄露：

- API key。
- auth token。
- bearer token。
- provider secret。
- service-manager auth token。
- cookie。
- 用户私有模型配置。

UI、日志、诊断报告、截图和导出文件只能显示脱敏值，例如：

```text
sk-****abcd
token: ****abcd
```

禁止直接打印包含 `env` 的完整 service JSON。诊断报告导出前必须脱敏。

## 版本和制品验收

核心制品必须固定版本并校验 sha256：

- Node。
- pi-agent payload。
- pi-web payload。
- SmallPhone payload。
- service-manager。
- openhouse-connect。
- CloudCLI。
- Claude Code 安装脚本或安装器。
- 可选 `cc-switch` arm64 二进制。

manifest 是唯一可信源。任何 payload 或二进制发生变化，都必须更新 manifest 中的 size 和 sha256。

## 放行标准

只有同时满足以下条件，才允许认为本轮实现达标：

- 文档同步完成。
- 安装状态机可恢复。
- 常规重试可用。
- 国内网络重试可用。
- 前台核心服务自动可用。
- 全部退出真实停止服务。
- 首次教学不要求用户学习终端。
- pi-agent 能引导配置 Claude 或 Codex。
- Claude 或 Codex 至少一个真实回复。
- 日志和诊断不泄露 key/token。
- 真机测试矩阵关键路径通过。
