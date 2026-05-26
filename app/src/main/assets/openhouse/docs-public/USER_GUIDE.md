# OpenHouse User Guide

本说明写给使用 OpenHouse 的普通用户。

## 你什么时候需要把文档发给 AI

以下场景都应该先让 AI 阅读 OpenHouse 起始文档：

- 第一次使用 AI
- 更换到新的、更强的大模型
- 更换到别的 agent 应用
- AI 明显不知道这套系统运行在哪里
- AI 不知道应该先读哪些文档

如果当前 AI 支持 skill、系统提示模板、知识卡片或长期记忆入口，也可以把 OpenHouse 的环境说明做成一个按需调用的 skill。

## 你的目标

你不需要自己解释整套系统。

你只需要把一段固定的话复制给 AI，让它先阅读 OpenHouse 的起始文档，再继续和你协作。

## 标准做法

把下面这段话完整复制给 AI：

```text
你现在运行在 OpenHouse 环境中。

这是一个基于 Termux 的 Ubuntu 环境。请先不要自行猜测环境结构，也不要先扫描整个系统。

请按下面顺序先阅读官方文档：

1. /data/data/com.termux/files/home/product-docs/official/START_HERE.md
2. /data/data/com.termux/files/home/product-docs/official/AGENT_GUIDE.md
3. /data/data/com.termux/files/home/product-docs/official/PATHS_AND_PORTS.md

如果 Ubuntu 中存在更短的入口路径，比如 ~/product-docs/official，请优先使用短路径读取同名文件。

阅读完成后：

- 用几句话总结你理解的运行环境
- 告诉我你将优先使用哪些文档路径
- 然后再继续执行我的后续要求
```

## 什么时候重新发一次

出现以下情况时，建议重新把上面的提示词发给 AI：

- 你切换到了新的模型
- 你切换到了新的 agent 软件
- AI 忘记了 OpenHouse 的路径和运行方式
- AI 开始把环境误判成普通 Linux、普通云主机或普通容器

## 你不需要自己解释什么

通常不需要你自己解释这些内容：

- Termux 是什么
- Ubuntu 是怎么安装的
- 文档放在哪
- OpenCode 端口默认是多少

这些都应该由 AI 先去读取官方文档后再理解。

## 最简用法

如果你只想发最短版本，可以用下面这段：

```text
请先阅读 OpenHouse 起始文档：

- /data/data/com.termux/files/home/product-docs/official/START_HERE.md
- /data/data/com.termux/files/home/product-docs/official/AGENT_GUIDE.md
- /data/data/com.termux/files/home/product-docs/official/PATHS_AND_PORTS.md

读完后先总结环境，再继续回答我。
```

## 如果 AI 支持 skill

如果你使用的 AI 支持 skill 或类似机制，推荐额外准备一个按需使用的环境 skill：

- 名称建议：`系统环境说明`
- 作用：只在 AI 不清楚环境时再调用
- 不要求每次自动读取

使用时可以直接对 AI 说：

```text
如果你不清楚当前环境，请先使用“系统环境说明”这个 skill，再继续回答我。
```

这个 skill 的标准内容模板见 `ENV_SKILL.md`。
