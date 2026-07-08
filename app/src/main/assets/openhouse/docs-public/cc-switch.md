# cc-switch 定位

cc-switch 可以作为 OpenHouse 的模型配置工具箱。它适合帮助 pi-agent 降低 provider 配置出错概率，但它不是主服务，也不是长期运行工作台。

## 可以做什么

cc-switch 可以帮助：

- 管理 Claude Code、Codex、Gemini、OpenCode、Hermes、OpenClaw 等工具的 provider 配置。
- 统一描述 provider、base URL、model id、协议和密钥字段。
- 辅助切换 provider。
- 做部分配置检测。
- 让弱模型少手写配置文件。

## 不能做什么

cc-switch 不负责：

- 安装 Claude Code 本体。
- 启动 CloudCLI 网页服务。
- 管理长期进程。
- 替代 service-manager。
- 替代 pi-agent 的解释和引导职责。
- 成为用户默认主工作台。

## 在 OpenHouse 中的位置

推荐定位：

```text
pi-agent 调用 cc-switch 作为配置执行器
```

不推荐定位：

```text
cc-switch 作为常驻服务
cc-switch 作为首次教学入口
cc-switch 替代 service-manager
```

如果内置 arm64 预编译二进制，应满足：

- 固定版本。
- sha256 校验。
- 写入 manifest。
- 不进入长期运行服务列表。
- 只在配置、检测、切换 provider 时调用。

当前 APK 内置版本：

| 字段 | 值 |
| --- | --- |
| 版本 | `5.9.0` |
| 源仓库 | `https://github.com/jiwuyou/cc-switch-cli.git` |
| 源提交 | `890c20f3cc39b090a1e79851eee3314eb8fcf2bc` |
| APK payload | `openhouse/product-payloads/cc-switch-cli-5.9.0-linux-arm64.tar.gz` |
| payload sha256 | `46ce26be4c1eddfc7a3407eac8820395a2da42db4cb9bf11bf9d4a87b1cfb20e` |
| binary sha256 | `5c59e8ea224d263c58f5665b64e54c9c334380e9e81210f3ee84708643d98cad` |

安装入口：

```bash
/root/openhouse/scripts/install-cc-switch.sh
```

## 与模型迁移的关系

pi-agent 收到用户的 `base_url`、`key/token`、`model id` 和协议类型后，可以先判断目标工具，再决定是否调用 cc-switch。

cc-switch 有价值的地方是把配置写入目标工具期望的位置和格式。AI 仍需负责：

- 向用户解释需要什么信息。
- 判断目标工具。
- 判断协议。
- 调用后测试。
- 失败后查文档和联网检索。

## 安全要求

调用 cc-switch 或展示其结果时，不得打印完整 key/token。需要展示配置摘要时必须脱敏。

不要把 cc-switch 的数据库、配置文件或导出内容直接贴到聊天或日志里，除非先确认不包含敏感字段。
