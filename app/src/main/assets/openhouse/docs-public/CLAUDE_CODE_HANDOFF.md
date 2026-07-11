# Claude Code 交接提示词

完成首次 OpenHouse 配置、测通 CloudCLI 中的 Claude Code 后，可以把下面这段话给用户复制到 Claude Code。

这段话只告诉 Claude Code 阅读 OpenHouse 文档和文档路径，不重新定义 Claude Code 的身份。

## 推荐复制内容

```text
请先阅读 OpenHouse 的内置文档，了解这个产品的能力、架构和使用方式。

文档目录：
/root/openhouse/docs

请优先查看：
/root/openhouse/docs/PRODUCT_OVERVIEW.md
/root/openhouse/docs/CAPABILITIES_MAP.md
/root/openhouse/docs/WORKBENCH_OPTIONS.md
/root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md
/root/openhouse/docs/MODEL_API_SETUP.md
/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md
/root/openhouse/docs/SERVICE_MANAGER.md
/root/openhouse/docs/RECOVERY.md
/root/openhouse/docs/AI_AGENT_REFERENCE.md

阅读后，请记住这些文档的位置、用途，以及在什么场景下应该回到对应文档查证。之后再和我继续聊天；如果我的问题涉及 OpenHouse 的能力、配置、服务控制、模型迁移或排障，请优先参考这些文档。
```

## 产品短名映射

如果界面或其它文档使用产品化短文件名，可按下表理解：

| 提示词中的路径 | 当前内置文档 |
| --- | --- |
| `openhouse-overview.md` | `PRODUCT_OVERVIEW.md`, `CAPABILITIES_MAP.md` |
| `pi-agent-first-use.md` | `OPENHOUSE_FIRST_CONFIGURATION.md`, `START_HERE.md` |
| `model-config-migration.md` | `MODEL_API_SETUP.md`, `OPENHOUSE_FIRST_CONFIGURATION.md` |
| CloudCLI / Claude Code 配置 | `CLOUDCLI_CLAUDE_CODE.md` |
| 服务控制 | `SERVICE_MANAGER.md` |
| 排障入口 | `TROUBLESHOOTING.md`, `RECOVERY.md`, `AI_AGENT_REFERENCE.md` |

如果 `/root/openhouse/docs` 不存在，使用兼容目录：

```text
/root/openhouseai-docs/official
```

## 不要加入的内容

交接提示词不要写：

- “你是 Claude Code”。
- “你的身份是 OpenHouse agent”。
- “你必须按照某某角色工作”。
- API key、token、账号密码或私有 URL。

只给文档入口和查证规则，避免覆盖 Claude Code 自己的系统提示词和工作方式。
