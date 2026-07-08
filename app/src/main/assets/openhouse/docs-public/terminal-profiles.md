# 终端分层

这是稳定小写入口。完整说明见 `TERMINAL_PROFILES.md` 和 `ENVIRONMENT.md`。

常见层级：

- Termux 外层：`/data/data/com.termux/files/home`
- Termux prefix：`/data/data/com.termux/files/usr`
- Ubuntu 内层：`/root`
- Ubuntu rootfs 真实位置：`/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu`

如果当前在 Termux，需要调用 Ubuntu：

```bash
proot-distro login ubuntu -- bash -lc 'pwd; whoami'
```

如果当前在 Ubuntu，需要处理 Termux 外层问题，优先通过 OpenHouse 的 Termux 终端入口或 App 维护入口，不要在 Ubuntu 内盲目修改 Termux prefix。
