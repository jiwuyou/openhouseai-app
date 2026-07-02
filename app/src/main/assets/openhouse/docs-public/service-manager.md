# service-manager

这是给 pi-agent 和其他 AI 工作台使用的稳定小写入口。完整说明见同目录：

- `SERVICE_MANAGER.md`
- `RECOVERY.md`
- `ENVIRONMENT.md`

service-manager 是安装完成后的运行控制平面。AI 管理后台服务时，默认应该通过 service-manager，而不是直接 `nohup`、后台 shell 或随意杀进程。

它负责：

- 服务列表和状态。
- 启动、停止、重启、修复。
- 服务日志和健康检查。
- `group:local-stack` 本地运行栈。
- OpenHouse 侧边栏和服务注册的后端绑定。

常用入口：

```bash
cd "$HOME/.smallphoneai-bootstrap"
bash bootstrap.sh status
bash bootstrap.sh start
bash bootstrap.sh repair
```

注册服务时，服务执行细节写入 service-manager 的 `ServiceSpec`；侧边栏入口写入 OpenHouse 组件注册。不要把命令、脚本和参数塞进侧边栏组件配置里。

