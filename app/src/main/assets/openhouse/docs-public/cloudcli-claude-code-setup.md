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

