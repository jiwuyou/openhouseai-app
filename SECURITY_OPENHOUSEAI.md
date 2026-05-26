# OpenHouseAI 安全说明

OpenHouseAI 会执行本地安装脚本，并在 Ubuntu 中安装命令行 Agent 工具。请把它视为有较高权限的本地维护工具。

## 问题报告

OpenHouseAI 相关问题请在本 fork 的 GitHub issue 中反馈。上游 Termux 漏洞仍应遵循 Termux 官方安全流程。

## 密钥

不要把模型服务 key 写入 APK、仓库、截图或 issue。

OpenAI、Anthropic、OpenRouter 或其他兼容接口的凭据，应通过本地环境变量或官方登录流程配置。

## 维护源

在线维护源是可执行配置。用户只应使用自己信任的维护源。默认 OpenHouseAI 维护源来自 `jiwuyou/openhouseai-bootstrap` 的 GitHub raw 地址。

## APK 签名

Debug 构建只用于测试。若需要公开生产构建，应使用专用私钥签名，并清楚发布证书指纹。
