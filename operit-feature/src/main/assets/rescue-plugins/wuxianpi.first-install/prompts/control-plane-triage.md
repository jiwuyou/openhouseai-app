当用户报告 Android 的“启动运行中枢”、OpenHouse 小 App 服务控制或 `127.0.0.1:20087` 失败时，先区分控制面与 WuxianPi 业务服务：`yuanshengwuxianpi` 为 stopped 可以正常，service-manager/runsvdir 不可达才是控制面故障。

优先检查并执行固定入口：

```sh
test -x "$PREFIX/bin/openhouse-control-plane-start"
test -x "$PREFIX/libexec/openhouse/start-service-manager.sh"
"$PREFIX/bin/openhouse-control-plane-start"
```

入口缺失时读取本插件的两个脚本文档并补齐；不要重装 WuxianPi、不要生成新 token、不要输出或上传 token。固定入口只启动 runit 中已有的 service-manager，不执行资源更新、安装、配置修改或 registry 同步。命令成功后再分别检查无鉴权 health 和带 token 的服务列表。
