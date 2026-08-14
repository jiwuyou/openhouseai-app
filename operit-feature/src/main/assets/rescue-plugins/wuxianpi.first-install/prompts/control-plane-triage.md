当用户报告 Android 的“启动运行中枢”、OpenHouse 小 App 服务控制或 `127.0.0.1:20087` 失败时，先区分控制面与 WuxianPi 业务服务：`yuanshengwuxianpi` 为 stopped 可以正常，service-manager/runsvdir 不可达才是控制面故障。

优先检查并执行固定入口：

```sh
test -x "$PREFIX/bin/openhouse-control-plane-start"
test -x "$PREFIX/libexec/openhouse/start-service-manager.sh"
"$PREFIX/bin/openhouse-control-plane-start"
```

入口缺失时读取本插件的两个脚本文档并补齐；不要重装 WuxianPi、不要生成新 token、不要输出或上传 token。固定入口只启动 runit 中已有的 service-manager，不执行资源更新、安装、配置修改或 registry 同步。命令成功后再分别检查无鉴权 health 和带 token 的服务列表。

长时间命令必须保留 `session_id`。普通工具输出可能只保留前 64 KiB；需要查看失败现场时读取 tmux 末尾：

```sh
tmux capture-pane -p -S -2000 -t wuxianpi-setup
```

首次安装工作流的外部 Termux 探针使用无副作用 `execute_termux_command`，输出比较必须忽略首尾空白；不要因为 `verify_termux_run_command` 的尾随换行假阴性阻塞安装。第一段只按需安装 `tmux`，不要安装 `libncursesw` 或执行 `pkg upgrade`。第二段完成后优先读取市场资源集合；市场不可用时才回退 APK 总包。激活前必须确认 `_termux-services-env.sh`、50/60 脚本和固定入口都存在且使用 Termux 绝对 shebang。
