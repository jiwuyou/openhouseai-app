# OpenHouse 概览

这是给 pi-agent 和其他 AI 工作台使用的稳定小写入口。完整说明见同目录：

- `PRODUCT_OVERVIEW.md`
- `CAPABILITIES_MAP.md`
- `USER_SCENARIOS.md`
- `WORKBENCH_OPTIONS.md`

OpenHouseAI 是一个面向人机协作的移动端 AI 运行平台。它把 Android、Termux、Ubuntu、服务控制、文件、内置浏览器、本地 Web 工作台和 AI 工具放在同一个可管理环境里，让用户和 AI 能围绕同一套能力协作。

首次安装的核心目标是建立控制平面：Termux / Ubuntu、Node、文档、pi-agent / pi-web、service-manager、openhouse-connect 和 SmallPhone 兼容服务。Codex、Claude Code、CloudCLI、Hermes 等是后置工作能力，由 pi-agent 根据用户目标和文档引导安装。

重要定位：

- `pi-agent` 是首次配置助手、文档索引员和配置迁移执行者，不是唯一主工作台。
- 用户可以选择 Claude Code、Codex、Hermes Web，或让 AI 搜索、安装和改造其他开源项目作为长期工作台。
- `service-manager` 是安装完成后的运行控制平面。
- `cc/codex` 是 CloudCLI / Claude Code / Codex 的统一入口；未安装时应提示用户先进入 pi-agent 完成后置配置。

