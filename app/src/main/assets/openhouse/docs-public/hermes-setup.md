# Hermes WebUI

这是稳定小写入口。完整说明见 `HERMES_SETUP.md` 和 `AI_TOOL_POSTINSTALL.md`。

Hermes 是可选高级工作台，安装和配置可能花很久。它不属于首次安装主链路，也不应该阻塞 pi-agent、pi-web 或 service-manager。

需要安装时，先阅读 `HERMES_SETUP.md`，再按需执行：

```bash
/root/openhouse/scripts/install-hermes.sh
```

脚本只准备基础环境和仓库。正式注册到 service-manager 前，应先前台测通 Hermes WebUI，再写入服务配置和侧边栏入口。
