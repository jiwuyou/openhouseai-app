# CloudCLI 中的 Claude Code 配置

本文档给 pi-agent 使用。目标是让用户在 `pi-agent` 中发起“配置 Claude Code”任务后，AI 能按文档配置并测通 `cc/codex` 入口中的 CloudCLI / Claude Code。

安装后的稳定路径：

```text
/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md
/root/openhouseai-docs/official/CLOUDCLI_CLAUDE_CODE.md
~/openhouseai-docs/official/CLOUDCLI_CLAUDE_CODE.md
```

pi-web 默认提示词应引用这个路径，而不是引用源码目录。

如果 `cloudcli` 命令或 `cc/codex` 服务缺失，先执行：

```bash
/root/openhouse/scripts/install-cloudcli.sh
```

再继续本文档的模型配置和测通步骤。

CloudCLI 的 Claude Code 模式会检查固定路径：

```text
/root/.local/bin/claude
```

如果页面或日志出现 `Claude Code native binary not found at /root/.local/bin/claude`，不要手工猜路径，直接执行：

```bash
/root/openhouse/scripts/install-claude-code.sh
/root/openhouse/scripts/install-cloudcli.sh
/root/openhouse/scripts/check-ai-tools.sh
```

脚本会在 npm 全局安装路径存在 `claude` 时自动补齐 `/root/.local/bin/claude`。

## 用户需要先提供

让用户在第一条消息里一次性给出：

```text
URL:
key/token:
model id:
协议类型或 provider 名称:
```

不要让用户把真实 key/token 写入仓库、APK 资源、共享文档、截图或日志。只把它写入本机配置文件，并尽量避免在命令输出中回显。

如果这些信息已经在 OpenHouseAI / AionUi 中测通，pi-agent 可以读取现有模型配置作为来源。AionUi 来源字段应按 `platform`、`name`、`base_url`、`api_key`、`models[]`、`model_protocols`、`model_health` 理解，并优先参考 AionUi 自带协议检测、Key 测试和模型健康检查结果；仍要确认 CloudCLI / Claude Code 当前版本需要的协议和配置格式。

## 入口关系

- Android 菜单里的一级入口是 `cc/codex`。
- `cc/codex` 是 CloudCLI / Claude Code / Codex 的统一入口。
- CloudCLI 的 OpenHouse 默认 Web 端口当前目标是 `23083`，实际端口以 service-manager 服务定义和 Android `cc/codex` 入口为准。
- CloudCLI 默认本地账号密码是 `admin / 123456`。
- 这组账号密码仅限本机首次使用；后续可以修改密码，尤其不要暴露到非本机网络。

测通目标是 CloudCLI 页面中的 Claude Code 能使用指定模型完成一次对话或任务，不是只在 Ubuntu 终端里执行 `claude --version`。

## pi-agent 的执行边界

首次安装不会自动安装或修复 CloudCLI / Claude Code 配置，也不会要求用户配置默认模型或 API key。

当用户选择“配置 Claude Code”提示词后，由 pi-agent 按本文档检查和修复：

1. 确认运行层是 Ubuntu。
2. 确认 CloudCLI 服务由 service-manager 管理。
3. 确认 `cc/codex` 入口可访问。
4. 根据用户提供的 URL、key/token、model id 配置 CloudCLI。
5. 可选安装并调用 cc-switch 作为 provider 配置执行器。
6. 检查并修复 `agent.js` 的模式配置。
7. 在 CloudCLI 中测通 Claude Code。
8. 提醒用户通过菜单进入 `cc/codex`，登录 `admin / 123456`，选择默认模型。

## bypasspermissions 与 root

CloudCLI 当前存在一个重要约束：权限模型可能硬编码为 `bypasspermissions`。OpenHouse 的主要项目环境运行在 Ubuntu 的 `/root` 用户下。

这意味着：

- CloudCLI 的 Claude Code 可能会以较高权限执行项目命令。
- `agent.js` 中的模式配置必须明确适配这种运行方式。
- pi-agent 不应假设默认权限模型已经正确。
- 在修复前，应告知用户会修改本机 CloudCLI/Claude Code 配置，不会修改 APK payload。

检查顺序：

```bash
cd /root
find /root/.cloudcli /root/.local /root/.npm-global /root/.config -name 'agent.js' -print 2>/dev/null
```

找到 CloudCLI 使用的 `agent.js` 后，先备份：

```bash
cp agent.js "agent.js.bak.$(date +%Y%m%d-%H%M%S)"
```

再按当前文件结构查找权限模式、模型配置、provider 配置和 Claude Code 调用参数。不要盲目整文件覆盖；只做最小必要修改。

## 配置检查

先确认服务状态：

```bash
resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1)
[ -n "$resource_dir" ] && [ -f "$resource_dir/bootstrap/bootstrap.sh" ] || { echo "未找到可用的 APK bootstrap 资源" >&2; exit 1; }
cd "$resource_dir/bootstrap"
bash bootstrap.sh status
service-manager status cloudcli 2>/dev/null || true
service-manager status cc-connect 2>/dev/null || true
```

如果 service-manager 不可用，先按 `SERVICE_MANAGER.md` 和 `RECOVERY.md` 修复控制中枢。

确认 CloudCLI 页面可访问：

```bash
curl -fsS --max-time 5 http://127.0.0.1:23083/ >/dev/null || true
```

实际端口以 service-manager 服务定义和 Android `cc/codex` 入口为准。

## 写入模型配置

具体配置路径以当前 CloudCLI 版本为准。pi-agent 应先读取现有配置和文档，再修改本机文件。

写入时必须包含用户提供的三项：

- URL
- key/token
- model id

如果 CloudCLI 支持网页配置，优先通过网页配置完成。如果需要写本机配置文件，先备份原文件，再写入最小变更。

## 可选使用 cc-switch

配置 Claude Code 时，可以可选使用 APK 内置的 `cc-switch` CLI 辅助写入和切换 provider。它适合在用户已经提供 `URL`、`key/token`、`model id` 和协议类型后，降低手写配置文件出错概率。

安装或检查：

```bash
/root/openhouse/scripts/install-cc-switch.sh
command -v cc-switch
cc-switch --version
```

使用边界：

- cc-switch 是 provider 配置执行器，不是 Claude Code 本体。
- cc-switch 不启动 CloudCLI，也不注册到 service-manager。
- 使用前先阅读 `/root/openhouse/docs/cc-switch.md`。
- 写入前先备份现有 Claude Code / CloudCLI 配置。
- 输出 provider 摘要时必须脱敏 key/token。
- 如果 cc-switch 当前版本不支持目标 provider、协议或 CloudCLI 配置路径，应回退到本文档的手动最小修改流程。

调用后仍必须完成 CloudCLI 页面中的 Claude Code 实测。只看到 `cc-switch` 命令成功，不算配置完成。

## 测通步骤

1. 通过 service-manager 重启 CloudCLI 或 `cc/codex` 相关服务。
2. 刷新 Android 页面或浏览器页面。
3. 使用 `admin / 123456` 登录 CloudCLI。
4. 选择配置好的默认模型。
5. 在 CloudCLI 中发起一次 Claude Code 任务。
6. 确认任务能收到回复，并能在允许范围内执行简单检查命令。

验收时必须确认：

- `/root/.local/bin/claude --version` 可执行。
- `cloudcli` 命令可执行。
- service-manager 中 `cloudcli` 是稳定服务 ID。
- health URL 指向 `http://127.0.0.1:23083`，旧端口或重复随机服务不会被 Android UI 控制到。
- CloudCLI 页面中的 Claude Code 能使用目标模型完成一次真实请求。

测通后，pi-agent 应提醒用户：

```text
Claude Code 已在 CloudCLI 中测通。请从菜单进入 cc/codex，使用 admin / 123456 登录，选择默认模型。这个默认账号密码仅限本机首次使用，后续可以修改。
```

## 失败时怎么做

如果配置失败，或本文档没有覆盖当前 CloudCLI 版本：

1. 不要反复覆盖同一配置文件。
2. 读取 service-manager 日志和 CloudCLI 日志。
3. 使用 pi-agent 的联网搜索工具检索当前 CloudCLI / Claude Code / ClaudeCodeUI 配置方法。
4. 把检索到的差异说明给用户，再执行最小修复。

推荐检索关键词：

```text
CloudCLI Claude Code agent.js bypasspermissions
ClaudeCodeUI Claude Code custom model base url token
Claude Code Anthropic compatible endpoint model id
```

## 相关文档

- `/root/openhouse/docs/MODEL_API_SETUP.md`
- `/root/openhouse/docs/SERVICE_MANAGER.md`
- `/root/openhouse/docs/RECOVERY.md`
- `/root/openhouse/docs/AI_AGENT_REFERENCE.md`
