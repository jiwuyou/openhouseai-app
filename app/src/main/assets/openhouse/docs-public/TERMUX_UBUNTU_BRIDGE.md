# Termux / Ubuntu Bridge

本文给 AI agent 和维护者使用，说明 OpenHouse 在同一台 Android 真机上如何让 Termux native 和 Ubuntu/proot 互相调用。

核心原则：

- 环境桥解决路径互通。
- `RUN_COMMAND` / Termux native `sshd` 解决身份正确。
- service-manager 解决长期服务生命周期。

控制面基线：service-manager 长期驻留在 Termux native 层。Termux native 服务使用 `termux-process` provider；Ubuntu/proot 内服务使用 `proot-distro` provider 或受控 wrapper。不要把 service-manager daemon 放进 Ubuntu/proot 长期运行。

## 运行层基线

真机验证确认，Termux native 和 Ubuntu/proot 是同一台 Android 设备上的两个运行层：

| 入口 | 身份 | HOME | Node |
| --- | --- | --- | --- |
| Termux native | Android/Termux app 用户，例如 `u0_aNNN` | `/data/data/com.termux/files/home` | `android/arm64` |
| Ubuntu/proot | `root`，但不是 Android root | `/root` | `linux/arm64` |

两边使用同一个 Android kernel 和同一台手机的网络栈。Termux Node 与 Ubuntu Node 架构不同，native addon、npm cache、二进制产物不能混用。

## Termux -> Ubuntu

一次性命令使用：

```bash
oh-ubuntu-root -- bash -lc 'whoami; echo "$HOME"; node -p "process.platform+\"/\"+process.arch"'
```

等价底层命令：

```bash
proot-distro login ubuntu -- bash -lc 'whoami; echo "$HOME"'
```

如果 Ubuntu 内后续存在非 root Linux 用户，可以用：

```bash
oh-ubuntu-user alice -- bash -lc 'whoami; echo "$HOME"'
```

长期 Ubuntu 服务应由 Termux native service-manager 的 `proot-distro` provider 管理，而不是由 Android UI 或临时 shell 直接后台启动。

注册 Ubuntu 长期服务时，ServiceSpec 必须说明运行层，例如：

```json
{
  "provider": "proot-distro",
  "runtime": {
    "distro": "ubuntu",
    "user": "root"
  }
}
```

如果服务后来安装到非 root Linux 用户，把 `runtime.user` 改成对应用户名；service-manager 仍保持在 Termux native 层负责控制。

## Ubuntu -> Termux

正式路径是：

1. Termux native 中 `oh-termux-ensure-sshd ensure` 确保本机回环 sshd 可用。
2. Ubuntu 内用 `openhouse-termux` 或 `oh-termux` 通过本机回环 ssh 回到 Termux native。首选端口是 `8022`；如果这个端口已被其它 app 容器占用，会自动选择 `8023-8039` 中的可用端口。

常用命令：

```bash
openhouse-termux status --json
openhouse-termux ensure-sshd
openhouse-termux exec -- 'id; echo "$HOME"; echo "$PREFIX"; node -p "process.platform+\"/\"+process.arch"'
```

如果 sshd 已停止，`openhouse-termux ensure-sshd` 会尝试通过 Android `RUN_COMMAND` 唤醒 Termux native 的 `oh-termux-ensure-sshd`。如果设备未允许外部命令执行，回到 Termux native 运行：

```bash
oh-termux-ensure-sshd ensure
```

不要在 Ubuntu 内直接执行 `/data/data/com.termux/files/usr/bin/bash`、`pkg`、`npm` 或其它 Termux prefix 二进制作为官方路径。那样路径可能看起来可用，但身份、Android API 行为、文件属主和长期进程归属都可能不正确。

## 首次安装接入

首次安装会做这些准备：

1. `10-prepare-termux.sh` 注入 `oh-ubuntu-root`、`oh-ubuntu-user`、`oh-termux-ensure-sshd`。
2. `12-update-termux-packages.sh` 安装 `openssh`、`curl` 和 `jq`，并启动/检查 Termux native `sshd`。
3. `20-install-ubuntu.sh` 向 Ubuntu 注入 `/usr/local/bin/openhouse-termux` 和 `/usr/local/bin/oh-termux`。
4. `30-update-ubuntu-packages.sh` 安装 `openssh-client` 和 `jq`。

当前首装把 `oh-termux-ensure-sshd` 作为底座修复入口；如果后续把 `termux-sshd` 注册为 service-manager 服务，服务命令也应调用这个幂等入口，而不是复制一份新的 sshd 启动逻辑。

APK 会把完整资源复制到版本化目录：

```text
/data/data/com.termux/files/home/.local/share/openhouseai/update-resources/apk-版本-versionCode-日期/product-payloads
```

版本与兼容性摘要来自该目录的 `manifest.json`，并会在维护动作日志头打印。AI 排障时优先看摘要里的 APK 版本、bootstrap tree hash、service-manager、registryApi、pi-agent、pi-web 和 aionui-web 条目。

真实端口和 Termux 用户名写在：

```text
/data/data/com.termux/files/home/.smallphoneai/termux-ssh-port
/data/data/com.termux/files/home/.smallphoneai/termux-ssh-user
```

AI 不要硬编码 `8022` 或用户名；在 Ubuntu 内优先调用 `openhouse-termux`。

## 排障

在 Termux native 检查：

```bash
oh-termux-ensure-sshd status
oh-termux-ensure-sshd ensure
ssh \
  -i "$HOME/.ssh/openhouse_termux_bridge_ed25519" \
  -p "$(cat "$HOME/.smallphoneai/termux-ssh-port" 2>/dev/null || printf 8022)" \
  -l "$(cat "$HOME/.smallphoneai/termux-ssh-user" 2>/dev/null || id -un)" \
  127.0.0.1 \
  'id; echo "$HOME"; echo "$PREFIX"'
```

在 Ubuntu 内检查：

```bash
openhouse-termux status --json
openhouse-termux exec -- 'id; echo "$HOME"; echo "$PREFIX"'
```

如果 Ubuntu 不可用，先回 Termux native 修：

```bash
proot-distro login ubuntu -- true
oh-ubuntu-root -- bash -lc 'cat /etc/os-release'
```

如果 Termux sshd 不可用，先回 Termux native 修，不要从 Ubuntu 直接改 Termux prefix：

```bash
oh-termux-ensure-sshd ensure
```

版本/桥接排障时优先收集：

```bash
tail -n 80 "$HOME/.maintainer-logs/manifest_full.log" 2>/dev/null || true
tail -n 80 "$HOME/.smallphoneai/logs/service-manager.log" 2>/dev/null || true
resource_dir=$(find "$HOME/.local/share/openhouseai/update-resources" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort | tail -n 1)
cat "$HOME/.local/share/openhouseai/update-resources/PENDING_APK_RESOURCES.json" 2>/dev/null || true
[ -z "$resource_dir" ] || sed -n '1,220p' "$resource_dir/product-payloads/manifest.json" 2>/dev/null || true
```
