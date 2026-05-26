# OpenHouse Official Rules

本文件定义 OpenHouse 的官方规则。

## 环境规则

- OpenHouse 运行在基于 Termux 的 Ubuntu 中
- 不要把当前环境视为普通 Linux 主机
- 不要默认把整个 Termux 主目录当作自由工作区

## 文档规则

- 官方文档目录用于提供权威说明
- agent-notes 只用于经验总结
- 当官方文档与 agent-notes 冲突时，以官方文档为准
- 不要直接修改官方文档，除非产品明确允许

## 路径规则

优先使用产品明确提供的固定路径。

当前核心目录：

- `/data/data/com.termux/files/home/product-docs/official`
- `/data/data/com.termux/files/home/product-docs/agent-notes`

如果 Ubuntu 中存在较短入口路径，例如：

- `~/product-docs/official`
- `~/product-docs/agent-notes`

则优先使用短路径。

## AI 行为规则

- 先读官方文档，再执行任务
- 不要默认扫描整个系统
- 不要在不明确的情况下自行修改系统级目录
- 如果不清楚环境，先查看 `START_HERE.md`
- 如果当前 AI 支持 skill，可按需使用“系统环境说明”

## 用户使用规则

- 第一次使用 AI 时，应先引导 AI 阅读官方文档
- 更换模型或更换 agent 应用后，建议重新进行引导
- 如果 AI 明显误判环境，应重新发送引导提示词或提醒其使用环境 skill
