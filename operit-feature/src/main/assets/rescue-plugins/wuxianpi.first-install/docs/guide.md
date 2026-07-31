# WuxianPi 首次安装

这个插件根据设备的真实状态继续执行，不维护脆弱的向导进度。再次运行时，已经完成的步骤会被检查并跳过。

## 两种宿主

- All-in-One 使用 APK 内部 Termux，不需要 SAF 或外部 RUN_COMMAND 授权。
- Native 使用外部 All-in-One Termux，需要授权 Termux Home，并允许 RUN_COMMAND。

## 执行顺序

1. 检查当前安装状态和宿主类型。
2. 准备 Termux 与 bootstrap。
3. Native 获取 Termux Home SAF，并完成工作区绑定。
4. Native 获取 RUN_COMMAND 权限并验证命令通道。
5. 测试软件源，只安装 tmux 的必要前置包。
6. 安装 tmux，建立持久 Termux 终端。
7. tmux 准备后，所有长命令和后续诊断使用 `termux_exec_command`。
8. 将 APK 随附资源转移到 Termux，运行可重复的一键安装器。
9. 安装 service-manager、WuxianPi 和 Ubuntu；暂不安装 AionUI 等可选组件。
10. 检查服务、端口、模型 API、Web UI 和终端工具。

安装中断后可以直接再次启动本工作流。
