# 系统环境说明

本文件定义一个给 agent 按需使用的环境说明 skill。

对 agent 的建议名称：

- `系统环境说明`

这个 skill 的目标不是在每次对话开始时自动注入，而是让 agent 在以下情况下按需查看：

- 不清楚当前运行环境
- 不清楚文档在哪里
- 错把当前环境当作普通 Linux、普通云主机或普通容器
- 需要确认 OpenHouse 的路径和基本约束

## 使用原则

- 不要求每次对话都读取
- 只在 AI 对环境不清楚时再调用
- skill 负责提供入口和规则
- 详细说明仍然去读官方文档

## 建议写入 skill 的内容

下面这段就是标准内容模板：

```text
这是 OpenHouse 的系统环境说明。

你当前处于 OpenHouse 环境中。

这是一个基于 Termux 的 Ubuntu 环境，不是普通 Linux 主机，也不是普通容器。

当你不清楚环境结构时，请优先检查以下官方文档：

- /data/data/com.termux/files/home/product-docs/official/START_HERE.md
- /data/data/com.termux/files/home/product-docs/official/AGENT_GUIDE.md
- /data/data/com.termux/files/home/product-docs/official/PATHS_AND_PORTS.md

如果 Ubuntu 中存在更短的入口路径，例如 ~/product-docs/official，请优先使用短路径。

规则：

- 先读官方文档，再继续任务
- 不要默认扫描整个 Termux 主目录
- 官方文档优先于 agent-notes
- 不要修改 official 文档
- 如果只需要环境定义和固定路径，优先从官方文档获取，不要自行猜测
```

## skill 不应该承载什么

不建议把以下内容直接写进 skill：

- 长篇安装教程
- 大量 FAQ
- 经常变化的端口细节
- 大量产品更新记录

这些内容应继续保留在官方文档中。

## 推荐配套方式

用户可以这样使用：

- “如果你不清楚当前环境，请先查看‘系统环境说明’”
- “先使用‘系统环境说明’，再继续回答”

如果当前 AI 不支持 skill，再退回到复制粘贴 `AI_BOOTSTRAP_PROMPT.md` 的方式。
