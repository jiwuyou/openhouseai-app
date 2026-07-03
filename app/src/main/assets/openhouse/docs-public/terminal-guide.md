# 终端教学

终端教学是高级入口，不进入首次教学。普通用户可以先完成 pi-agent 和 Claude/Codex 配置，以后需要排障、开发或高级自动化时再阅读本文档。

## 三层路径

| 层级 | 常见路径 | 用途 |
| --- | --- | --- |
| Android App | 菜单、维护中心、WebView、终端入口 | 权限、状态、显式控制。 |
| Termux 外层 | `/data/data/com.termux/files/home`, `/data/data/com.termux/files/usr` | 底座、bootstrap、Termux 包、proot-distro、Ubuntu 启停。 |
| Ubuntu 内 | `/root`, `/root/openhouse/docs`, `/root/projects` | 主要工作区、pi-agent/pi-web、AI CLI、开发工具。 |

Ubuntu rootfs 在 Termux 文件系统中的真实位置通常是：

```text
/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu
```

知道这个路径有助于排障，但不要默认直接修改 rootfs 内部文件。优先通过 Ubuntu 终端或 `proot-distro login ubuntu` 操作。

## 判断当前在哪

运行：

```bash
pwd
echo "$HOME"
cat /etc/os-release 2>/dev/null || true
command -v proot-distro 2>/dev/null || true
```

判断：

- `$HOME=/root` 且 `/etc/os-release` 显示 Ubuntu：当前在 Ubuntu 内。
- `$HOME=/data/data/com.termux/files/home`：当前在 Termux 外层。
- `proot-distro` 可用且 home 是 Termux 路径：可以从 Termux 管理 Ubuntu。

## Termux 调 Ubuntu

当前在 Termux 外层，需要运行 Ubuntu 命令时：

```bash
proot-distro login ubuntu -- bash -lc 'pwd; whoami'
```

示例：

```bash
proot-distro login ubuntu -- bash -lc 'cd /root && ls -la'
proot-distro login ubuntu -- bash -lc 'cd /root/openhouse/docs && ls'
```

如果已经在 Ubuntu 内，不要再嵌套 `proot-distro login ubuntu`。

## Ubuntu 调 Termux

当前在 Ubuntu 内，需要处理 Termux 外层问题时，不要盲目直接执行 Termux prefix 中的二进制。优先：

1. 使用 OpenHouse 的 Termux 终端入口。
2. 使用 App 维护入口。
3. 使用已有 bridge 或 bootstrap 脚本。
4. 查 `SERVICE_MANAGER.md`、`RECOVERY.md` 和 `AI_AGENT_REFERENCE.md`。

没有明确文档时，应告诉用户需要回到 Termux 外层执行。

## 默认选择

| 任务 | 默认终端 |
| --- | --- |
| 编程、构建、测试、用户项目 | Ubuntu |
| pi-agent、pi-web、MCP、AI CLI | Ubuntu |
| Claude Code、Codex、CloudCLI | Ubuntu |
| 安装 Ubuntu、修复 proot-distro | Termux |
| Termux 包、Android 桥、底座修复 | Termux |
| service-manager 长期服务 | 不直接开终端，优先 service-manager |

## 禁止事项

- 不要把终端教学放进首次教学。
- 不要让普通用户为了开始使用 Claude/Codex 先学终端。
- 不要在 Ubuntu 内盲目修改 `/data/data/com.termux/files/usr`。
- 不要删除 Termux home、Ubuntu rootfs、用户项目或模型配置，除非用户明确确认。
- 不要把 key/token 打进终端日志。
