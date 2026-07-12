# OpenHouse 国内网络重试

本文定义 OpenHouse 首次安装中的“国内网络重试”固定路径。它的目标不是自动选择理论最快源，而是提供一条可验证、可复现、少选择、稳定完成安装的国内路径。

适用范围：

- Termux 基础包下载失败。
- Ubuntu rootfs 下载过慢或失败。
- Ubuntu apt 更新失败。
- Node、npm、CloudCLI、后置工具下载失败。
- GitHub 资源访问失败。
- OpenHouse payload 下载失败或缓存损坏。

## 总原则

1. 国内网络重试必须是一条确定路径，不让普通用户选择多个镜像。
2. 国内网络重试只改变下载源和网络策略，不删除用户数据。
3. 所有非包管理器签名保护的下载必须 sha256 校验。
4. sha256 不匹配必须失败，不能继续安装。
5. 国内路径失败时必须保留脱敏日志，方便 AI 或开发者排障。
6. 国内网络重试不能打印 key/token。
7. 国内网络重试不能重置用户模型配置。

## 用户入口

安装失败页显示两个主要按钮：

```text
常规重试
国内网络重试
```

当失败原因符合以下任一情况时，UI 应推荐“国内网络重试”：

- `curl timeout`
- `Could not resolve host`
- `Connection reset`
- `TLS handshake timeout`
- `apt update` 源不可达
- `npm install` 长时间无进度
- GitHub release 下载失败
- Ubuntu rootfs 下载过慢
- payload 下载失败

用户不需要理解镜像源。点击“国内网络重试”后，系统沿用 canonical 有序故障转移策略、增加 transient failure 的重试强度，并从失败阶段继续。

## 状态机继承

国内网络重试不另建状态机，必须继承 `openhouse-install-flow.md` 定义的阶段状态：

```text
pending
running
succeeded
failed
skipped
retrying
```

国内网络重试只允许从 `failed` 或用户明确要求重新验证的 `succeeded` 阶段进入 `retrying`，再进入 `running`。完成后只能进入 `succeeded` 或 `failed`。不得因为文件残留从 `retrying` 直接进入 `skipped`。

## 重试策略概览

| 类型 | 国内网络重试策略 | 校验方式 |
| --- | --- | --- |
| Termux apt | 固定使用一个主源，失败后按固定 fallback 顺序切换。 | apt 包签名校验。 |
| Ubuntu rootfs | 与常规模式共用 canonical 四源顺序和运行级 lock；CN 只增加 transient failure 重试。 | 受控 Range、归档完整性、架构和最小体积校验，记录最终 URL。 |
| Ubuntu apt | 与 rootfs 共用 canonical 策略；只写入 `openhouseai-ubuntu.sources`。 | InRelease 探测和 apt 包签名校验。 |
| Node | 优先 APK 内置或 OpenHouse payload；否则使用国内 Node mirror。 | sha256 校验。 |
| npm registry | 固定使用国内 npm registry。 | npm integrity 校验，记录 registry。 |
| GitHub release | 固定使用 GitHub 镜像或 OpenHouse payload fallback。 | sha256 校验。 |
| OpenHouse payload | 优先 APK 内置；需要下载时使用固定 payload 源。 | manifest sha256 校验。 |
| cc-switch 可选二进制 | 使用内置 arm64 二进制或固定下载源。 | sha256 校验。 |

## Canonical 源顺序

下一轮实现应把源配置集中管理，避免散落在多个脚本里。建议命名为：

```text
OPENHOUSE_NETWORK_MODE=default
OPENHOUSE_NETWORK_MODE=cn
```

Ubuntu rootfs 与 apt 不因 CN/normal 改变顺序，统一为：

```text
TUNA -> NJU -> Ubuntu official -> USTC
```

第一轮每源最多 16 秒；只有 transient failure 进入第二轮，每源最多 32 秒。选中结果绑定本次安装 run ID，后续阶段不重新测速。其它资源策略如下：

| 资源 | 首选 | 固定 fallback |
| --- | --- | --- |
| Termux apt | `https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main` | `https://mirrors.ustc.edu.cn/termux/apt/termux-main`，`https://mirrors.bfsu.edu.cn/termux/apt/termux-main`，`https://mirrors.nju.edu.cn/termux/apt/termux-main` |
| Ubuntu rootfs | TUNA | NJU，Ubuntu official，USTC |
| Ubuntu apt | TUNA | NJU，Ubuntu official，USTC |
| npm registry | `https://registry.npmmirror.com` | 官方 registry 只作为最后 fallback，并明确标记为非国内路径 |
| Node dist | `https://npmmirror.com/mirrors/node` | OpenHouse 自有 payload 源 |
| GitHub release | OpenHouse 固定镜像或自有 payload 源 | 官方 GitHub 只作为最后 fallback |
| OpenHouse payload | APK 内置 payload | OpenHouse 自有国内对象存储或 release mirror |

如果某个源未经过当前版本真机验证，不得放入首选路径。

## Termux apt 国内重试

目标阶段：

- `termux-packages`

固定行为：

1. 备份当前 Termux apt source 文件。
2. 写入国内首选源。
3. 执行 `pkg update` 或等价 apt update。
4. 安装基础包。
5. 如果失败，按固定 fallback 顺序切换一次。
6. 成功后记录实际使用的源。

成功条件：

- `curl --version` 成功。
- `proot-distro --help` 成功。
- `tar --version` 或等价命令成功。
- `xz --version` 或等价命令成功。
- `ca-certificates` 已安装或 TLS 下载可用。

失败条件：

- 所有固定源 apt update 均失败。
- apt/dpkg 数据库损坏且自动修复失败。
- 设备空间不足。

不得清理：

- Termux home 中用户文件。
- 用户手动配置的非 OpenHouse 文件。
- 模型配置和 token。

## Ubuntu rootfs 国内重试

目标阶段：

- `install_ubuntu`

固定行为：

1. 按 canonical 四源顺序执行受控 Range 探测，不下载完整探测文件。
2. 对 transient failure 执行第二轮有界重试；永久错误不重试。
3. 锁定本次运行选中的 URL；如已有未完成下载，继续断点续传。
4. 下载完成后校验归档完整性、架构、最小体积和可用的服务端长度信息。
5. 执行 `proot-distro install -n ubuntu <rootfs>` 或等价安装。
6. 安装后执行 `proot-distro login ubuntu -- true`。

成功条件：

- rootfs sha256 匹配。
- Ubuntu 能登录。
- Ubuntu 内 `/etc/os-release` 可读且 ID 为 Ubuntu。
- Ubuntu 内 `~/bin/smallphoneai-env-probe` 可执行。

失败条件：

- rootfs 下载失败。
- sha256 不匹配。
- proot-distro 解包失败。
- Ubuntu 登录失败。

强制重试边界：

- 允许清理当前阶段未通过校验的 rootfs 临时文件。
- 允许清理当前阶段临时解包目录。
- 不允许删除已可登录的 Ubuntu rootfs，除非用户明确选择“重装 Ubuntu”。

## Ubuntu apt 国内重试

目标阶段：

- `ubuntu-packages`

固定行为：

1. 复用本次运行已锁定的 apt mirror；没有 lock 时按 canonical 顺序探测 `InRelease`。
2. 备份 Ubuntu base source 的已知 managed 文件。
3. 原子写入 `/etc/apt/sources.list.d/openhouseai-ubuntu.sources`，清理旧 `smallphoneai-ubuntu.sources`，保留第三方 PPA。
4. 执行 apt update。
5. 安装基础包并记录实际使用的源。

成功条件：

- apt update 成功。
- `git`、`curl`、`python3`、`bash`、`tar`、`xz` 等基础命令按阶段需要可执行。

失败条件：

- apt 源不可达。
- 包冲突无法自动修复。
- 空间不足。
- proot syscall 限制导致 apt 无法运行。

不得清理：

- `/root` 用户数据。
- `/root/openhouse/docs`。
- `/root/workspace`。
- 模型配置。

## Node 和 npm 国内重试

目标阶段：

- `install_node`
- Claude/Codex/CloudCLI 后置配置阶段。

固定行为：

1. 优先使用 APK 内置 Node payload 或 OpenHouse payload。
2. 若必须下载，使用固定国内 Node mirror。
3. 下载后执行 sha256 校验。
4. npm registry 固定为 `https://registry.npmmirror.com`。
5. npm 安装必须设置合理超时和重试次数。
6. 安装完成后执行版本检查。

成功条件：

- `node --version` 成功。
- `npm --version` 成功。
- npm global bin 目录在 PATH。
- 需要的 CLI 命令可执行。

失败条件：

- Node sha256 不匹配。
- npm registry 不可达。
- npm integrity 校验失败。
- npm 安装超时。

不得清理：

- `/root/.npm` 中已校验可用的缓存。
- `/root/.claude`。
- `/root/.codex`。
- `/root/.cloudcli`。
- 用户 key/token。

## GitHub 资源国内重试

GitHub 资源包括：

- release 二进制。
- tarball/zipball。
- install script。
- 文档引用的上游项目。

固定行为：

1. 核心组件不应在首次安装时依赖 GitHub 实时下载。
2. 首次安装所需核心组件必须优先来自 APK 内置 payload。
3. 若必须下载 GitHub 资源，使用 OpenHouse 自有镜像或固定 GitHub 镜像。
4. 下载后必须 sha256 校验。
5. 上游 install script 不得直接联网执行，除非脚本内容和版本已经固定并校验。

成功条件：

- 下载文件 sha256 匹配。
- 解包后包含预期可执行文件。
- 版本检查通过。

失败条件：

- 镜像返回 HTML 错误页。
- sha256 不匹配。
- 解包结构不符合预期。
- 二进制架构不匹配。

## OpenHouse payload 国内重试

目标阶段：

- `runtime_components`
- `sync_openhouse_registry`
- 后置工具安装。

固定行为：

1. 优先使用 APK 内置 payload。
2. 如果内置 payload 缺失或损坏，使用固定国内 payload 源。
3. payload manifest 必须包含：
   - name
   - version
   - size
   - sha256
   - target architecture
   - expected executable paths
4. 下载后先校验 sha256，再解包。
5. 解包后检查 expected executable paths。

成功条件：

- manifest 存在。
- sha256 匹配。
- 解包成功。
- service-manager、pi-agent、pi-web、openhouse-connect、smallphone 的必要文件存在且权限正确。

失败条件：

- manifest 缺失。
- sha256 不匹配。
- payload 架构不匹配。
- 解包后缺少必要可执行文件。

强制重试边界：

- 允许删除未通过 sha256 校验的 payload 缓存。
- 允许删除当前 payload 的临时解包目录。
- 允许刷新 OpenHouse 管理的运行组件目录。
- 不允许删除用户数据目录和模型配置。

## sha256 校验规范

必须校验：

- Ubuntu rootfs。
- Node 压缩包或内置 payload。
- service-manager payload。
- pi-agent payload。
- pi-web payload。
- openhouse-connect payload。
- smallphone payload。
- CloudCLI/Claude/Codex 相关预编译二进制。
- cc-switch arm64 二进制，如果内置或下载。

校验记录格式：

```text
artifact_name
artifact_version
artifact_url
expected_sha256
actual_sha256
size_bytes
result
```

日志中可记录 URL，但如果 URL 包含 token 或签名参数，必须脱敏 query string。

sha256 不匹配时：

1. 标记当前阶段 `failed`。
2. 删除该未通过校验的临时文件。
3. 保留脱敏日志。
4. 不继续解包。
5. 不写成功 marker。

## 缓存策略

允许复用：

- 已通过 sha256 校验的 payload。
- apt 包管理器自己的缓存。
- npm integrity 校验通过的缓存。
- 已安装且健康检查通过的组件。

必须丢弃：

- sha256 不匹配的下载文件。
- 明显是 HTML 错误页的 release 文件。
- 0 字节文件。
- 未完成下载临时文件。
- 解包失败的临时目录。

不得丢弃：

- 用户工作目录。
- 用户模型配置。
- key/token。
- 历史日志。
- `/root/openhouse/docs` 中用户新增内容。
- service-manager auth token。

## 日志脱敏

国内网络重试会涉及更多 URL、registry 和下载日志，因此必须执行脱敏。

不得打印：

- 完整 API key。
- 完整 token。
- Authorization header。
- service-manager auth token。
- provider secret。
- 带签名参数的完整下载 URL。
- service JSON 的完整 `env` 字段。

允许打印：

```text
registry=https://registry.npmmirror.com
artifact=smallphone.tar
expected_sha256=...
actual_sha256=...
token=****abcd
url=https://example.com/path/file.tar.gz?<redacted>
```

## 国内网络重试完成条件

国内网络重试不能只以“命令退出 0”为成功。必须同时满足：

1. 当前失败阶段健康检查通过。
2. 该阶段下载产物通过校验。
3. 后续依赖阶段没有立即失败。
4. 状态机从 `retrying` 进入 `succeeded`。
5. 日志记录使用了 `retry_mode=cn`。

如果国内网络重试成功，后续阶段继续使用国内网络模式，直到本次安装完成。安装完成后，不应强行改写用户手动配置的源。

## 与常规重试的区别

| 行为 | 常规重试 | 国内网络重试 |
| --- | --- | --- |
| 下载源 | 默认源或已有测速逻辑 | 固定国内路径 |
| 用户选择源 | 不需要 | 不需要 |
| 缓存复用 | 复用校验通过缓存 | 复用校验通过缓存 |
| sha256 | 必须执行 | 必须执行 |
| apt 源 | 保持当前源 | 写入 OpenHouse 管理的国内源配置 |
| npm registry | 保持当前配置 | 使用固定国内 npm registry |
| GitHub | 默认策略 | 使用固定镜像或 payload fallback |
| 用户数据 | 不清理 | 不清理 |

## AI 排障提示

当 pi-agent、Claude Code 或 Codex 被要求排查国内网络失败时，应优先读取：

1. `openhouse-install-flow.md`
2. `openhouse-cn-network-retry.md`
3. `TROUBLESHOOTING.md`
4. 当前阶段脱敏日志

AI 应先判断：

- 是 DNS、TLS、超时、sha256 不匹配，还是解包失败。
- 当前处于哪个阶段。
- 当前 retry mode 是 `default` 还是 `cn`。
- 是否误用文件存在作为成功条件。
- 是否需要强制重试当前阶段。

AI 不应要求用户直接删除：

- Ubuntu rootfs。
- `/root/.claude`
- `/root/.codex`
- `/root/.cloudcli`
- `/root/openhouse/docs`
- `/root/workspace`
- service-manager config

除非用户明确确认要重置对应数据。
