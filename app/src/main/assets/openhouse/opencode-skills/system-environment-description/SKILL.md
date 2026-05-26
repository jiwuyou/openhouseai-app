---
name: system-environment-description
description: 系统环境说明：当你不清楚当前环境、文档路径或 OpenHouse 与 Termux/Ubuntu 的关系时使用。
---

# 系统环境说明

这是 OpenHouse 的系统环境说明。

你当前运行在基于 Termux 的 Ubuntu 中，不是普通 Linux 主机，也不是普通容器。

当你不清楚环境结构时，请优先检查以下官方文档：

- `/data/data/com.termux/files/home/product-docs/official/START_HERE.md`
- `/data/data/com.termux/files/home/product-docs/official/AGENT_GUIDE.md`
- `/data/data/com.termux/files/home/product-docs/official/PATHS_AND_PORTS.md`

如果 Ubuntu 中存在更短的入口路径，例如 `~/product-docs/official`，请优先使用短路径。

规则：

- 先读官方文档，再继续任务
- 不要默认扫描整个 Termux 主目录
- 官方文档优先于 `agent-notes`
- 不要修改 `official` 文档
- 如果只需要环境定义和固定路径，优先从官方文档获取，不要自行猜测
