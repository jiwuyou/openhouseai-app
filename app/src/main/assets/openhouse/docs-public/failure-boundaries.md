# 失败恢复边界

本文档说明哪些问题应自动修复，哪些问题应提示用户确认重置或重装。

## 自动修复范围

以下问题可以默认自动修复或通过“修复运行控制”处理：

- service-manager 未运行。
- 核心服务未注册。
- 核心服务停止。
- `smallphone`、`pi-agent`、`cloudcli` 健康检查失败。
- service-manager 中存在重复随机服务 ID。
- CloudCLI 端口注册错误。
- `/root/.local/bin/claude` 缺失但 npm 全局 claude 存在。
- 文档未同步或 `/root/openhouse/docs` 缺少入口文件。
- 阶段 marker 缺失但实际健康检查通过。

自动修复不得删除用户数据、模型配置、项目目录和日志。

## 需要用户确认的操作

以下操作必须先让用户明确确认：

- 删除 `/data/data/com.termux/files/home`。
- 删除 Ubuntu rootfs。
- 重装 Ubuntu。
- 重建 Termux prefix。
- 清除 App 数据。
- 删除 `/root/projects`。
- 覆盖 Claude/Codex/CloudCLI 模型配置。
- 停止不属于 OpenHouse 管理的长期进程。
- 导出包含日志和配置的诊断报告。

## 建议重置或重装的情况

遇到以下情况，自动修复失败后可以建议用户重置或重装：

- Termux prefix 关键二进制损坏，`sh`、`proot-distro` 或包管理器不可用。
- Ubuntu proot 无法启动，且 rootfs 基础目录缺失或严重损坏。
- service-manager 配置损坏，备份恢复失败。
- payload sha256 多次不一致，且重新下载后仍失败。
- 文件系统空间不足导致关键依赖无法解包。
- Android 系统持续杀进程，后台权限和电池优化设置仍无法解决。

建议重置前必须说明会保留什么、会删除什么，并尽可能提供备份步骤。

## 诊断优先级

排障顺序：

1. 确认用户当前看到的界面和错误。
2. 查看安装或运行阶段状态。
3. 检查 service-manager。
4. 检查核心服务健康。
5. 检查 Termux/Ubuntu 分层。
6. 检查网络和 sha256。
7. 检查权限和系统后台限制。
8. 最后才建议重置或重装。

## 安全要求

诊断报告必须脱敏。不得包含完整：

- API key
- token
- authorization header
- cookie
- service-manager auth token
- provider secret

如果用户要求复制日志，应先生成脱敏摘要。
