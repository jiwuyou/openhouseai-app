# OpenHouse AI Bootstrap Prompt

本文件提供给用户直接复制发送给 AI。

## 标准提示词

```text
你现在运行在 OpenHouse 环境中。

这是一个 OpenHouse 环境。当前 AI 可能运行在 Termux native，也可能运行在 Termux 中的 Ubuntu。请先识别运行层，不要自行猜测环境结构，也不要先扫描整个系统。

请按下面顺序先阅读官方文档：

1. /data/data/com.termux/files/home/openhouse/docs/START_HERE.md
2. /data/data/com.termux/files/home/openhouse/docs/AGENT_GUIDE.md
3. /data/data/com.termux/files/home/openhouse/docs/PATHS_AND_PORTS.md

如果当前位于 Ubuntu，使用等价短路径 `/root/openhouse/docs`。

阅读完成后：

- 用几句话总结你理解的运行环境
- 告诉我你将优先使用哪些文档路径
- 然后再继续执行我的后续要求
```

## 短提示词

```text
请先阅读 OpenHouse 起始文档：

- /data/data/com.termux/files/home/openhouse/docs/START_HERE.md
- /data/data/com.termux/files/home/openhouse/docs/AGENT_GUIDE.md
- /data/data/com.termux/files/home/openhouse/docs/PATHS_AND_PORTS.md

读完后先总结环境，再继续回答我。
```
