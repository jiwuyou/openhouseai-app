# Android 控制和 Shizuku 可选增强

OpenHouse 的基础能力来自 Android App、Termux 和 Ubuntu。Shizuku 是可选的 Android 控制增强能力，不参与也不阻塞首次安装。

OpenHouse APK 内带一份未修改的官方 Shizuku APK，用户可以在“权限获取”页面点击“安装内置 Shizuku”。安装动作会进入 Android 系统安装器，OpenHouse 不会静默安装、自动启动服务或自动请求授权。

## 最短使用流程

1. 打开 OpenHouse 的“权限获取”。
2. 点击“安装内置 Shizuku”，在系统安装器中确认安装。
3. 打开 Shizuku，按照其页面提示通过无线调试、ADB 或 root 启动服务。
4. 返回 OpenHouse，点击“请求 Shizuku 授权”。
5. 在 Shizuku 官方授权框中允许 OpenHouse 使用 Shizuku。
6. 在 Termux 执行 `rish -c 'id'` 验证终端通道。

部分厂商系统会延迟显示授权框。点击请求后若没有立即出现，请切回 Shizuku 查看“是否允许 OpenHouse AI 使用 Shizuku”的提示。

## Shizuku 能带来什么

在用户安装、启动并授权 Shizuku 后，OpenHouse 可以逐步接入更强的 Android 侧操作能力，例如：

- 查询应用安装状态。
- 启动应用或跳转系统设置页。
- 辅助处理部分权限设置。
- 读取更完整的系统状态。
- 执行部分需要更高 Android 权限的操作。

具体能力取决于：

- Android 版本和厂商系统。
- Shizuku 是否正在运行。
- 用户是否授权 OpenHouse。
- OpenHouse 是否已经实现对应接口。
- 当前设备的安全策略。

AI 不能承诺“授权后一定能完全控制真机”。

## 使用前检查

AI 在尝试 Android 控制能力前，应先确认：

1. 用户是否希望使用 Android 侧增强能力。
2. Shizuku 是否已安装并运行。
3. OpenHouse 是否获得授权。
4. 当前操作是否会影响系统设置、应用数据、隐私或费用。
5. 是否有更低风险的普通 Intent、设置页或手动步骤。

## 风险确认

以下操作必须先向用户确认：

- 修改系统设置。
- 授权、撤销授权或调整安全相关选项。
- 清理应用数据或缓存。
- 卸载、停用或强行停止应用。
- 读取可能包含隐私的信息。
- 自动化点击或替用户完成敏感操作。

## 与 Termux 的关系

Termux 更适合：

- 文件操作。
- 命令执行。
- Ubuntu 启停和修复。
- 开发工具链。
- 网络请求和日志检查。

Shizuku 更贴近：

- Android 系统服务。
- 应用和权限状态。
- 真机设置页和系统能力。

AI 应优先选择风险更低、可解释性更强的路径。

## 在 Termux 中使用 rish

OpenHouse 会把与内置 Shizuku 配套的 `rish` 和 `rish_shizuku.dex` 准备到 Termux 原生环境。Shizuku 服务已经启动并且 OpenHouse 获得授权后，可执行：

```bash
rish -c 'id'
rish -c 'whoami'
rish -c 'pm list packages | head'
```

无线调试或 ADB 启动的 Shizuku 通常返回 `uid=2000(shell)`；root 模式通常返回 `uid=0(root)`。

`rish` 只负责连接已经运行的 Shizuku 服务，不能代替无线调试、ADB 或 root 去首次启动 Shizuku。手机重启后，非 root 模式的 Shizuku 服务可能需要重新启动。

## 文档和检索

Shizuku、Android 权限和厂商系统差异变化很快。如果内置文档不足，AI 应联网检索当前官方说明、设备限制和已知问题，不要根据旧经验硬猜。
