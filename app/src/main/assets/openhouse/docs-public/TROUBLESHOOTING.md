# OpenHouse Troubleshooting

本文件用于处理最常见的理解和使用问题。

## 问题：AI 不知道自己运行在哪

处理方式：

1. 让 AI 先阅读 `START_HERE.md`
2. 再阅读 `AGENT_GUIDE.md`
3. 再阅读 `PATHS_AND_PORTS.md`

如果当前 AI 支持 skill，也可以直接提示：

```text
如果你不清楚当前环境，请先使用“系统环境说明”这个 skill，再继续回答我。
```

## 问题：AI 把环境当成普通 Linux

现象包括：

- 随意扫描整个系统
- 假设自己在云主机中
- 假设文档在普通项目目录里

处理方式：

- 重新发送 `AI_BOOTSTRAP_PROMPT.md` 中的标准提示词
- 明确告诉 AI：这是基于 Termux 的 Ubuntu 环境
- 要求其先总结环境，再继续回答

## 问题：AI 找不到文档

先检查以下路径是否存在：

- `/data/data/com.termux/files/home/product-docs/official`
- `~/product-docs/official`

如果都不存在，说明官方文档可能尚未同步。

建议：

- 通过维护器执行文档同步
- 或检查文档是否只存在于开发目录而未发布

## 问题：AI 只看到了 Ubuntu 视角，看不到 Termux 视角

这通常不是 AI 的能力问题，而是入口说明不足。

处理方式：

- 明确告诉它 Termux 主目录基础路径
- 让它以官方文档中的固定路径为准
- 不要求它自行探索整个环境

## 问题：切换了新模型后，AI 又忘了环境

这是正常情况。

处理方式：

- 重新发送标准引导提示词
- 或重新提醒其使用“系统环境说明” skill

## 问题：换了别的 agent 应用后行为不一致

不同 agent 应用对以下能力的支持不同：

- 文件读取
- 长期记忆
- skill
- 系统提示

处理方式：

- 保留一份复制粘贴版提示词
- 同时准备一份按需使用的环境 skill
- 不依赖某一个 agent 专属能力
