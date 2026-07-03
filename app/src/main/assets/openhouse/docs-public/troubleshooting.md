# 排障入口

这是给 pi-agent 和其他 AI 工作台使用的稳定小写入口。完整说明见同目录：

- `RECOVERY.md`
- `SERVICE_MANAGER.md`
- `ENVIRONMENT.md`
- `GITHUB_NETWORK_MIRRORS.md`
- `openhouse-install-flow.md`
- `openhouse-runtime-repair.md`
- `failure-boundaries.md`
- `permissions.md`

排障原则：

1. 先诊断，不直接重装。
2. 先恢复 service-manager，再恢复上层服务。
3. Ubuntu 坏了，用 Termux 修。
4. Termux 坏了，用 Android App 的维护/底座修复能力。
5. 默认保留用户项目、模型配置、API key、本地知识库和 agent 笔记。
6. 清数据、删除 home、重装 Ubuntu、重建 prefix 都需要用户明确确认。

快速检查：

```bash
cd "$HOME/.smallphoneai-bootstrap"
bash bootstrap.sh status
bash bootstrap.sh check
bash bootstrap.sh hooks
```

如果 pi-web、pi-agent 或 CloudCLI 不可访问，优先看 service-manager 状态。如果后置工具缺失，执行 `/root/openhouse/scripts/check-ai-tools.sh`，再按需安装，不要把后置工具缺失当作首装失败。

## 症状到文档

| 症状 | 优先阅读 | 处理方向 |
| --- | --- | --- |
| 首次安装卡住 | `openhouse-install-flow.md` | 找到当前阶段、状态、日志和重试入口。 |
| 国内网络下载失败 | `openhouse-cn-network-retry.md` | 使用固定国内路径重试，不让用户手动选源。 |
| 等待页误判成功 | `openhouse-install-flow.md` | 检查阶段 marker 和健康检查是否一致。 |
| service-manager 不可达 | `openhouse-runtime-repair.md`, `service-manager.md` | 先恢复控制平面，再恢复上层服务。 |
| pi-agent 不可达 | `openhouse-runtime-policy.md`, `service-manager.md` | 检查服务注册、端口、健康状态。 |
| CloudCLI 不可达 | `cloudcli-claude-code-setup.md` | 检查 `cloudcli` 稳定服务 ID 和端口 `23083`。 |
| Claude Code native binary 缺失 | `cloudcli-claude-code-setup.md` | 重新执行安装脚本补齐 `/root/.local/bin/claude`。 |
| Codex 配置失败 | `codex-setup.md`, `model-config-migration.md` | 区分官方登录和 API 模式，按协议配置。 |
| 用户想暂时省资源或结束本次使用 | `openhouse-exit-all.md` | 区分“停止运行栈”和“全部退出 OpenHouse”，不要只返回页面。 |
| 权限缺失 | `permissions.md` | 判断是否阻塞当前目标，缺失可选权限时降级。 |
| 自动修复失败 | `failure-boundaries.md` | 判断是否需要用户确认重置或重装。 |

## 标准排障顺序

1. 记录用户看到的界面、步骤、按钮和错误文案。
2. 判断当前任务是安装、运行、教学、模型配置还是终端排障。
3. 检查当前层级：Android App、Termux 外层或 Ubuntu 内。
4. 读取当前阶段状态和最近日志。
5. 检查 service-manager。
6. 检查核心服务：`smallphone`、`pi-agent`、`cloudcli`。
7. 检查端口健康和 UI 状态是否一致。
8. 如果是模型问题，检查协议、base URL、model id 和脱敏后的认证摘要。
9. 选择最小修复动作。
10. 修复后重新执行健康检查。

不要从“重装”开始。重置 Termux、删除 Ubuntu 或清 App 数据都需要用户明确确认。

## 安装失败

安装失败时，先按 `openhouse-install-flow.md` 找当前阶段。每个阶段都应有：

- 状态：`pending`、`running`、`succeeded`、`failed`、`skipped`、`retrying`。
- 成功条件。
- 失败条件。
- 日志位置。
- 常规重试行为。
- 国内网络重试行为。
- 强制重试清理范围。

失败后不要因为残留文件直接跳过。只有健康检查完整通过，才允许进入 `succeeded` 或 `skipped`。

## 运行失败

运行失败时，先恢复 service-manager。service-manager 存活后，再检查：

- `smallphone`
- `pi-agent`
- `cloudcli`
- 新增的长期服务

如果服务列表中出现同名随机服务 ID，应按 `openhouse-runtime-repair.md` 清理重复记录，并保留稳定服务 ID。

## 停止运行栈或全部退出后恢复

停止运行栈或全部退出 OpenHouse 后，核心端口不可达是正常结果。停止运行栈会保留当前 App 界面，并暂停本次会话自动保活；用户可以点击“恢复默认核心服务”重新拉起。全部退出 OpenHouse 会关闭 OpenHouse 界面，并请求关闭 Termux 前台服务和终端会话。再次打开 App 时，若高级设置没有关闭自动保活，应重新启动 service-manager，并恢复默认长期服务。

如果再次打开后没有恢复，检查：

- Android 是否暂停了前台检测。
- service-manager 是否能启动。
- 默认长期服务是否仍在 registry 中。
- 是否被后台权限或电池优化限制。

## 日志安全

任何排障日志和诊断报告都不得输出完整：

- API key
- token
- authorization header
- cookie
- service-manager auth token
- provider secret

需要展示时只能使用脱敏形式：

```text
sk-****abcd
token: ****abcd
```

不要直接打印包含 `env` 的完整 service JSON。导出诊断报告前必须脱敏。
