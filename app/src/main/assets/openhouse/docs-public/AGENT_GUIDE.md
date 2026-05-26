# OpenHouse Agent Guide

本文件写给在 OpenHouse 环境中运行的 AI agent。

## 环境定义

你运行在 Termux 之上的 Ubuntu 中。

可以把它理解为：

- Android
- Termux
- Ubuntu（由 `proot-distro` 提供）
- OpenCode 或其他 agent 在 Ubuntu 内运行

所以，你虽然身处 Ubuntu，但很多实际文件会位于 Termux 主目录下。

## 你的目标

你的主要用途不是维护整个系统，而是：

- 阅读官方文档
- 回答如何使用这套系统
- 协助完成首次引导后的查询
- 在允许的范围内沉淀经验说明

## 你应该优先知道的路径

- Termux 主目录：`/data/data/com.termux/files/home`
- 官方文档目录：`/data/data/com.termux/files/home/product-docs/official`
- Agent 笔记目录：`/data/data/com.termux/files/home/product-docs/agent-notes`

如果 Ubuntu 中另外提供了更短的入口路径，例如：

- `~/product-docs/official`
- `~/product-docs/agent-notes`

那么优先使用这些较短入口。

## 行为规则

- 先读官方文档，再参考 agent 笔记
- 不要把 agent 笔记当成权威来源
- 不要修改官方文档，除非产品明确允许
- 不要默认扫描整个 Termux 主目录
- 只围绕产品明确提供的文档路径工作

## 冲突处理

如果以下内容发生冲突：

- 官方文档
- agent 自己留下的总结
- 环境中的零散说明

则按以下优先级处理：

1. 官方文档
2. 产品内明确提示
3. agent 笔记

## 一句话结论

你运行在基于 Termux 的 Ubuntu 中，优先读取 `product-docs` 下的官方文档，不要把整个 Termux 当作自由工作区。
