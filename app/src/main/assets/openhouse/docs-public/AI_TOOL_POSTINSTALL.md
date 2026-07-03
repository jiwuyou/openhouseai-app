# 后置 AI 工具安装入口

OpenHouse 首次安装只建立控制平面：Termux / Ubuntu、Node、文档、pi-agent / pi-web、service-manager、openhouse-connect 和 SmallPhone 兼容服务。Codex、Claude Code、CloudCLI 和 Hermes 是后置能力，由 pi-agent 在用户配置好 pi-web 模型后引导安装、配置、检查和修复。

运行期脚本目录：

```text
/root/openhouse/scripts
```

兼容来源目录：

```text
/data/data/com.termux/files/home/openhouseai-scripts
```

## 脚本入口

| 脚本 | 用途 |
| --- | --- |
| `/root/openhouse/scripts/install-codex.sh` | 后置安装或检查 Codex CLI。 |
| `/root/openhouse/scripts/install-claude-code.sh` | 后置安装或检查 Claude Code。 |
| `/root/openhouse/scripts/install-cloudcli.sh` | 后置安装或检查 CloudCLI / ClaudeCodeUI，刷新服务注册，并只尝试启动 `cloudcli` 本服务。 |
| `/root/openhouse/scripts/install-hermes.sh` | 准备 Hermes WebUI 的独立 uv 环境；前台测通后再注册服务。 |
| `/root/openhouse/scripts/check-ai-tools.sh` | 检查 Node、Codex、Claude Code、CloudCLI、文档、脚本和基础运行栈。 |

这些脚本必须可以反复执行。已经安装时应检查版本和路径；缺失时再安装；失败时输出下一步，不应删除用户项目、清理 Ubuntu 或覆盖密钥。

Claude Code 的后置检查必须同时满足两点：

- `claude --version` 可执行。
- `/root/.local/bin/claude --version` 可执行，因为 CloudCLI 的 Claude Code 模式会读取这个固定路径。

## pi-agent 推荐顺序

用户完成 pi-web 模型配置后，pi-agent 应按这个顺序处理：

1. 阅读 `/root/openhouse/docs/START_HERE.md`、`OPENHOUSE_FIRST_CONFIGURATION.md`、`MODEL_API_SETUP.md`、`SERVICE_MANAGER.md`。
2. 执行 `/root/openhouse/scripts/check-ai-tools.sh`，确认哪些后置能力缺失。
3. 如果用户需要 Codex，执行 `install-codex.sh`。
4. 如果用户需要 Claude Code，执行 `install-claude-code.sh`。
5. 如果用户需要 `cc/codex` 网页入口，执行 `install-cloudcli.sh`，再按 `CLOUDCLI_CLAUDE_CODE.md` 配置模型并测通。
6. 如果用户明确选择 Hermes，执行 `install-hermes.sh` 准备环境，然后按 `HERMES_SETUP.md` 前台测通和注册 service-manager。
7. 完成后再次运行 `check-ai-tools.sh`，并把成功项、失败项和下一步告诉用户。

## 运行层要求

默认在 Ubuntu 内执行。若当前在 Termux 外层，可以使用：

```bash
proot-distro login ubuntu -- bash -lc '/root/openhouse/scripts/check-ai-tools.sh'
```

如果当前在 Ubuntu 内，不要嵌套调用 `proot-distro login ubuntu -- ...`。

从 Ubuntu 需要 Termux 外层能力时，优先回到 OpenHouse 的 Termux 终端入口或使用 App 维护入口。不要在 Ubuntu 内盲目修改 `/data/data/com.termux/files/usr`。

## 安全规则

- 不把 API key、token、cookie 写进仓库、APK 资源、公共文档、日志或截图。
- 写入本机配置前先说明将修改哪里，必要时备份原文件。
- CloudCLI 默认本机账号密码 `admin / 123456` 只用于首次本机使用，后续可修改。
- 不把“命令存在”当成“模型已测通”。CloudCLI / Claude Code 必须完成一次最小请求。
- 不把 Hermes 当成默认核心能力。它是可选高级工作台，安装耗时较久。

## 失败处理

如果脚本失败：

1. 先运行 `/root/openhouse/scripts/check-ai-tools.sh`。
2. 读取 `/root/openhouse/docs/RECOVERY.md` 和 `/root/openhouse/docs/GITHUB_NETWORK_MIRRORS.md`。
3. 检查 bootstrap 日志和 service-manager 状态。
4. 如果本地文档没有覆盖当前版本，使用 pi-agent 的联网搜索工具查询官方 README、release、issue 和当前安装文档。
5. 只做最小修复，不重装 Ubuntu，不删除用户项目。
