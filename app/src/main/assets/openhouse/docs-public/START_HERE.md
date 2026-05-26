# OpenHouse Start Here

你当前面对的是 OpenHouse 的最小官方说明。

## 你运行在哪里

你运行在 Android 手机上的 Ubuntu 用户空间中。

这套 Ubuntu 不是独立虚拟机，而是运行在 Termux 之上，通常通过 `proot-distro` 进入。

## 先确认的事实

- Termux 主目录基础路径：`/data/data/com.termux/files/home`
- 当前 Ubuntu 依赖 Termux 提供的宿主环境
- OpenHouse 官方文档由维护器同步到 Termux 主目录下的文档目录

## 你首先该看什么

1. 阅读 `AGENT_GUIDE.md`
2. 阅读 `PATHS_AND_PORTS.md`
3. 如需判断实际环境，再查看 Ubuntu 中是否能访问这些路径
4. 如果你是用户，请阅读 `USER_GUIDE.md`
5. 如果你准备给 AI 配置按需调用的环境 skill，请阅读 `ENV_SKILL.md`
6. 如果你需要规则、常见问题或排障说明，请继续阅读 `OFFICIAL_RULES.md`、`FAQ.md` 和 `TROUBLESHOOTING.md`

## 最重要的约定

- 这是一个基于 Termux 的 Ubuntu 环境
- 优先使用产品明确提供的固定路径
- 不要默认扫描整个 Termux 主目录
- 如官方文档与其他笔记冲突，以官方文档为准
