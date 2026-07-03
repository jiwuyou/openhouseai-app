# CloudCLI / Claude Code 配置

这是给 pi-agent 使用的稳定小写入口。完整说明见同目录：

- `CLOUDCLI_CLAUDE_CODE.md`
- `MODEL_API_SETUP.md`
- `SERVICE_MANAGER.md`
- `AI_TOOL_POSTINSTALL.md`

CloudCLI / Claude Code 是后置能力，不属于首次安装主链路。缺失时不要判定 OpenHouse 首装失败，应先确认控制平面可用，再按脚本安装：

```bash
/root/openhouse/scripts/install-claude-code.sh
/root/openhouse/scripts/install-cloudcli.sh
/root/openhouse/scripts/check-ai-tools.sh
```

如果出现：

```text
Claude Code native binary not found at /root/.local/bin/claude
```

直接重新执行上面的 `install-claude-code.sh` 和 `install-cloudcli.sh`。脚本会检查并补齐 CloudCLI 需要的 `/root/.local/bin/claude`。

配置 Claude Code 时，让用户一次性提供：

- URL 或 base URL
- key/token
- model id
- provider 或协议类型，如果用户知道

注意事项：

- 默认本机账号密码 `admin / 123456` 只用于首次本机使用，后续可以修改。
- OpenHouse 主要运行在 Ubuntu 的 `/root` 下，CloudCLI 的 `agent.js` 权限模式需要按 `CLOUDCLI_CLAUDE_CODE.md` 检查和修复。
- 测通目标是 CloudCLI 页面中的 Claude Code 能使用指定模型完成一次请求，不是只运行 `claude --version`。
- 如果当前 CloudCLI / Claude Code 版本行为和文档不同，应联网检索官方文档、README、issue 和 release。

## 配置边界

CloudCLI / Claude Code 是后置 AI 工作能力。它们不应阻塞 OpenHouse 核心安装完成，但会影响用户能否从 `cc/codex` 入口真正使用 Claude。

安装和配置必须由 pi-agent 按文档或脚本引导，不能要求普通用户手工理解每个配置文件。

## 必查项

配置前检查：

```bash
/root/openhouse/scripts/check-ai-tools.sh
command -v claude || true
test -x /root/.local/bin/claude && /root/.local/bin/claude --version
```

如果 `/root/.local/bin/claude` 不存在，运行：

```bash
/root/openhouse/scripts/install-claude-code.sh
```

如果 CloudCLI 不存在或服务不可达，运行：

```bash
/root/openhouse/scripts/install-cloudcli.sh
```

## 端口和服务

CloudCLI 的 OpenHouse 默认网页端口是：

```text
23083
```

service-manager 中稳定服务 ID 应是：

```text
cloudcli
```

验收时必须确认：

- service-manager 中 `cloudcli` 指向正确命令。
- health URL 指向 `http://127.0.0.1:23083`。
- 旧端口或重复随机服务不会被 Android UI 控制到。

## 权限模式

CloudCLI 可能依赖 Claude Code 的 `agent.js` 权限模式。OpenHouse 主要工作目录在 Ubuntu 的 `/root`，如果 CloudCLI 对项目路径或权限模式硬编码不兼容，应按 `CLOUDCLI_CLAUDE_CODE.md` 中的说明修复，并记录到排障日志。

修复应由 pi-agent 按文档执行；不要让普通用户直接编辑复杂 JS 文件。

## 实测标准

完成配置后，必须至少验证：

1. `/root/.local/bin/claude --version` 可执行。
2. `cloudcli` 命令可执行。
3. service-manager 中 `cloudcli` 是稳定 ID。
4. `http://127.0.0.1:23083/` 可访问。
5. CloudCLI 页面中 Claude Code 能使用目标模型完成一次请求。

只看到命令版本不代表配置成功。
