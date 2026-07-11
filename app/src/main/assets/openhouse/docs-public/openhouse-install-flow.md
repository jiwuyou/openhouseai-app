# OpenHouse 首次安装链路

本文定义 OpenHouse 首次安装链路、阶段状态机、重试语义和安全边界。它是下一轮实现安装页、安装脚本、pi-agent 引导和真机验收的共同依据。

最高目标：用户安装 APK 后，不理解 Termux、Ubuntu、Node、service-manager，也能完成首次安装，并至少成功使用 Claude 或 Codex 之一收到真实回复。

## 范围

本文只描述首次安装链路和重试行为，不描述首次教学、运行控制 UI、模型配置细节。相关文档：

- `openhouse-cn-network-retry.md`：国内网络重试固定路径。
- `SERVICE_MANAGER.md`：安装完成后的控制平面。
- `openhouse-runtime-policy.md`：前台长期运行策略。
- `pi-agent-first-use.md`：pi-agent 首次配置助手。
- `model-config-migration.md`：模型配置迁移。
- `CLOUDCLI_CLAUDE_CODE.md`：Claude Code 与 CloudCLI。
- `codex-setup.md`：Codex。
- `TROUBLESHOOTING.md`：排障。

## 安装原则

1. 首次可用优先于功能完整。
2. 普通用户不需要进入终端。
3. 安装阶段必须幂等，可从失败阶段继续。
4. 阶段成功必须由健康检查确认，不能只靠文件存在判断。
5. 网络下载必须有 sha256 校验或等价完整性校验。
6. 手机端避免重型构建，优先使用 APK 内置 payload、预编译二进制和固定版本。
7. 重试不得删除用户数据、模型配置、key/token、工作目录和历史日志。
8. 日志必须脱敏，不得打印完整 key/token、service-manager token 或 provider secret。
9. service-manager 是安装完成后的控制平面，安装链路不能把它作为最早的硬依赖。
10. 安装完成后，App 前台默认保持 `service-manager`、`smallphone`、`pi-agent`、`cloudcli` 可用。

## 路径约定

| 名称 | 路径 | 说明 |
| --- | --- | --- |
| Termux home | `/data/data/com.termux/files/home` | Android 侧 Termux 主目录。 |
| Termux prefix | `/data/data/com.termux/files/usr` | Termux 包和命令目录。 |
| Bootstrap root | `/data/data/com.termux/files/home/.smallphoneai-bootstrap` | APK 释放的安装脚本、assets、阶段状态和日志。 |
| Ubuntu root | `/root` | proot-distro Ubuntu 内的 root home。 |
| OpenHouse docs | `/root/openhouse/docs` | AI 和用户共同读取的正式文档目录。 |
| OpenHouse scripts | `/root/openhouse/scripts` | 后置安装、检查、修复脚本目录。 |
| Runtime repos | `/root/smallphoneai-repos` | service-manager、openhouse-connect、smallphone、pi-agent、pi-web 等运行组件。 |
| service-manager config | `/root/.config/openhouseai/service-manager/config.json` | service-manager 本机配置，包含 auth token，日志和 UI 不得原样打印。 |
| Stage state | `$TERMUX_HOME/.smallphoneai-bootstrap/state` | 阶段状态、marker、当前 run id。 |
| Stage logs | `$TERMUX_HOME/.smallphoneai-bootstrap/logs` | 分阶段日志。 |

## 状态机

每个安装阶段必须支持以下状态：

| 状态 | 含义 | UI 行为 |
| --- | --- | --- |
| `pending` | 尚未开始。 | 显示等待安装。 |
| `running` | 正在执行。 | 显示当前阶段、简要进度、查看详细日志入口。 |
| `succeeded` | 阶段健康检查通过。 | 自动进入下一阶段。 |
| `failed` | 阶段命令失败、超时或健康检查失败。 | 显示失败原因、常规重试、国内网络重试、查看日志。 |
| `skipped` | 明确判断已经完成且健康检查通过，因此跳过。 | 显示已完成，不应静默跳过关键阶段。 |
| `retrying` | 用户选择重试后重新执行。 | 显示重试类型和本次重试使用的策略。 |

允许的状态流转：

```text
pending -> running -> succeeded
pending -> running -> failed
failed -> retrying -> running -> succeeded
failed -> retrying -> running -> failed
pending -> skipped
succeeded -> retrying -> running -> succeeded
```

不允许的状态流转：

- `failed -> skipped`：失败后不能因为残留文件直接跳过。
- `running -> skipped`：执行中不能跳过。
- `pending -> succeeded`：没有运行或健康检查时不能直接成功。

## 阶段记录格式

每个阶段必须写入一份机器可读状态，建议 JSON 或 line-based key/value。字段至少包含：

```text
stage_id
stage_name
state
run_id
started_at
finished_at
retry_mode
attempt
exit_code
health_summary
log_path
safe_error_message
```

`safe_error_message` 必须脱敏。不得包含完整 key、token、Authorization header、service-manager auth token、provider secret。

## 首次安装阶段顺序

目标顺序如下。当前已有脚本名用于对齐实现，下一轮可以在不改变用户体验的前提下拆分或合并内部脚本，但 UI 和状态机必须保留这些产品阶段。

| 顺序 | 阶段 ID | 当前脚本 | 运行环境 | 阶段目标 |
| --- | --- | --- | --- | --- |
| 1 | `prepare` | `prepare-product.sh` | Termux | 确认真机 Termux 环境、架构、路径、权限基础可用，并准备 bootstrap 目录、assets、环境探测命令和基础配置。 |
| 2 | `termux_packages` | `update-termux-packages.sh` | Termux | 安装或修复 curl、proot-distro、openssh、tar、xz、ca-certificates 等基础包。 |
| 3 | `install_termux_node` | `install-termux-node.sh` | Termux | 安装或检查 Termux native Node.js 24 LTS/npm，供 pi-agent 和 pi-web 常驻服务使用。 |
| 4 | `runtime_components` | `install-runtime-components.sh` | Termux | 先确保 Termux native service-manager 可用，再按稳定 ID 安装、检查并注册 pi-agent 和 pi-web。 |
| 5 | `start_smallphone` | `start-smallphone.sh` | Termux | 通过 Termux native service-manager 启动 pi-agent 和 pi-web，让主入口先可用。 |
| 6 | `install_ubuntu` | `install-ubuntu.sh` | Termux | 安装 Ubuntu rootfs，并注入 Ubuntu 侧环境探测命令和 `openhouse-termux` 桥接 CLI。 |
| 7 | `ubuntu_packages` | `update-ubuntu-packages.sh` | Ubuntu | 更新 Ubuntu apt 索引，安装基础运行包、`openssh-client`、`git`、`gh`、`ripgrep` 和 `jq`。 |
| 8 | `entry_ubuntu` | `configure-entry-ubuntu.sh` | Termux | 配置进入 Ubuntu 的入口和模式文件。 |
| 9 | `install_node` | `install-node.sh` | Ubuntu | 安装 Ubuntu Node.js 24 LTS 和 npm 基础配置，供 AionUI、工作台和 AI CLI 工具使用。 |
| 10 | `sync_official_docs` | `sync-official-docs.sh` | Ubuntu | 同步 `/root/openhouse/docs` 和 `/root/openhouse/scripts`。 |
| 11 | `install_aionui` | `install-aionui.sh` | Ubuntu | 从 APK 内置离线包安装 AionUi 工作台，并检查本机入口。 |
| 12 | `sync_openhouse_registry` | `sync-openhouse-registry.sh` | Termux + Ubuntu | 同步 OpenHouse registry、service-manager 服务定义和侧边栏入口，避免同名随机服务。 |

核心长期服务目标：

- `service-manager`
- `smallphone`
- `pi-agent`
- `cloudcli`

`cloudcli` 是前台默认可用服务，但 Claude Code/Codex 的账号、模型和 token 由首次配置阶段完成。CloudCLI 服务可以先启动到“等待配置”状态，不应因用户尚未提供模型 key 阻塞基础安装。

状态机完成后还有一个后置引导任务：`first_ai_setup`。它不在 Android App 的机器驱动阶段数组中，由 pi-agent 根据本文档、`MODEL_API_SETUP.md`、`CLOUDCLI_CLAUDE_CODE.md` 和 `codex-setup.md` 引导用户配置 Claude 或 Codex，并实测至少一个工具能收到真实回复。

## 每阶段验收条件

| 阶段 ID | 成功条件 | 失败条件 | 超时建议 | 日志位置 | 常规重试 | 国内网络重试 |
| --- | --- | --- | --- | --- | --- | --- |
| `prepare` | `PREFIX`、`HOME`、`/data/data/com.termux/files` 可用；CPU 架构受支持；bootstrap 目录可写；assets 可访问；环境探测命令可执行。 | 非 Termux 环境；目录不可写；关键权限缺失；APK assets 缺失；释放不完整。 | 60s | `logs/prepare.log` | 重新释放缺失 assets，保留已有完整文件。 | 同常规重试。 |
| `termux_packages` | `curl`、`proot-distro`、`openssh`、`tar`、`xz`、`ca-certificates` 可执行或可被包管理器确认已安装；`oh-termux-ensure-sshd` 可用。 | apt update 失败；包下载失败；dpkg/apt 锁冲突；curl 不可用。 | 15min | `logs/termux-packages.log` | 使用默认 Termux 源和已有缓存重试。 | 切换到固定国内 Termux 源后重试。 |
| `install_termux_node` | Termux native `node --version` 和 `npm --version` 成功；Node major >= 24；npm prefix 和 PATH 写入 Termux profile。 | Termux pkg/apt 失败；Node 不可用；版本不符合；PATH 未生效。 | 30min | `logs/install-termux-node.log` | 使用 Termux 默认源和已有缓存重试。 | 切换到固定国内 Termux 源后重试。 |
| `runtime_components` | service-manager、pi-agent、pi-web payload sha256 通过；Termux native 服务清单使用稳定 ID；必要脚本可执行。 | payload 缺失；sha256 不匹配；解包失败；组件健康检查失败；注册出同名随机服务。 | 20min | `logs/runtime-components.log` | 使用 APK 内置 payload 重试，仅刷新 OpenHouse 管理目录。 | 使用国内 payload fallback，必须 sha256 通过。 |
| `start_smallphone` | service-manager API 可访问；`pi-agent`、`pi-web` 至少进入 running 或 configured-waiting 状态。 | service-manager 不可达；核心服务端口不可达；启动命令失败。 | 5min | `logs/start-smallphone.log` | 重启 service-manager 并按稳定 ID 拉起核心服务。 | 同常规重试。 |
| `install_ubuntu` | `proot-distro login ubuntu -- true` 成功；`~/bin/smallphoneai-env-probe` 在 Ubuntu 内可执行；`openhouse-termux` 可安装。 | rootfs 下载失败；解包失败；proot-distro 安装失败；Ubuntu 登录失败。 | 60min | `logs/install-ubuntu.log` | 使用默认测速结果和缓存重试。 | 使用固定国内 Ubuntu cloud image 路径重试，并校验 rootfs。 |
| `ubuntu_packages` | Ubuntu 内 `apt-get update` 成功；`openssh-client`、`git`、`gh`、`ripgrep`、`jq` 和基础包可执行或 apt 确认安装。 | apt 源不可达；包冲突；磁盘空间不足。 | 30min | `logs/ubuntu-packages.log` | 使用当前 Ubuntu apt 源重试。 | 写入固定国内 Ubuntu apt 源后重试。 |
| `entry_ubuntu` | Termux 入口文件存在；模式文件存在；从 Termux 能进入 Ubuntu。 | shell rc 文件不可写；proot-distro login 失败。 | 60s | `logs/entry-ubuntu.log` | 重写 OpenHouse 管理的入口片段。 | 同常规重试。 |
| `install_node` | Ubuntu 内 `node --version` 和 `npm --version` 成功；版本符合固定版本范围；npm global bin 在 PATH。 | Node 下载失败；解包失败；PATH 未生效；版本不符合。 | 30min | `logs/install-node.log` | 使用默认 Node payload 或默认源重试。 | 使用国内固定 Node mirror 或内置 payload 重试，并校验 sha256。 |
| `sync_official_docs` | `/root/openhouse/docs` 存在；P0 文档可读；`/root/openhouse/scripts/check-ai-tools.sh` 可执行。 | 文档目录缺失；脚本未同步；权限错误。 | 120s | `logs/sync-official-docs.log` | 重新同步 docs/scripts，不删除用户自有文件。 | 同常规重试。 |
| `install_aionui` | AionUi 离线包完整；本机入口可打开；不污染 Termux native pi-agent/pi-web。 | 离线包缺失；解包失败；入口不可达；配置混用 service-manager token。 | 20min | `logs/install-aionui.log` | 重新解包并检查入口，不删除用户项目。 | 同常规重试。 |
| `sync_openhouse_registry` | service-manager 服务定义使用稳定 ID；侧边栏入口定义存在；无同名随机重复服务。 | registry 写入失败；服务定义缺字段；重复服务未清理。 | 120s | `logs/sync-openhouse-registry.log` | 重新应用 registry，不删除用户数据。 | 同常规重试。 |
## 跳过规则

阶段只有同时满足以下条件才允许 `skipped`：

1. 上一次状态是 `succeeded`，或本次健康检查完整通过。
2. 该阶段的版本、sha256、端口、API 或命令探测满足要求。
3. 当前重试模式没有要求强制刷新该阶段。
4. 没有发现同阶段的半安装 marker。

禁止用以下条件单独判断成功：

- 目录存在。
- 文件存在。
- marker 文件存在。
- 上一次日志最后一行包含“完成”。
- npm、apt、curl 进程曾经启动过。

## 重试入口

安装失败时 UI 必须提供：

- `常规重试`
- `国内网络重试`
- `查看详细日志`

可选提供：

- `强制重试当前阶段`
- `复制脱敏诊断摘要`

默认推荐顺序：

1. 常规重试。
2. 常规重试仍失败且错误明显是网络问题时，建议国内网络重试。
3. 健康检查误判、残留半安装状态、sha256 不一致时，建议强制重试当前阶段。

## 常规重试

常规重试的语义：

- 从失败阶段继续，不从头安装。
- 复用已下载且校验通过的缓存。
- 复用已安装且健康检查通过的组件。
- 不切换源，除非当前脚本已有自动测速逻辑。
- 不删除用户配置、模型配置、workspace、日志。
- 对于健康检查失败的阶段，必须重新执行健康检查，不能只看 marker。

常规重试适用：

- 临时网络抖动。
- 用户切后台导致安装中断。
- service-manager 启动较慢。
- apt/npm 临时锁。
- 端口启动稍慢。

## 国内网络重试

国内网络重试的语义：

- 使用固定、稳定、少选择的国内路径。
- 不让用户手动选择多个源。
- 对 Termux apt、Ubuntu apt、Ubuntu rootfs、Node、npm、payload、GitHub 访问采用固定策略。
- 所有下载必须校验 sha256 或使用包管理器签名校验。
- 失败后保留脱敏日志，供下一轮排障。

详细策略见 `openhouse-cn-network-retry.md`。

## 强制重试当前阶段

强制重试不是重装系统。它只能清理当前阶段的 OpenHouse 管理产物。

允许清理：

- 当前阶段的 `running`、`failed`、`partial` marker。
- 当前阶段临时下载文件。
- 当前阶段未通过 sha256 校验的缓存。
- 当前阶段生成的临时解包目录。
- OpenHouse 管理的重复 service-manager 服务定义。
- 明确标记为 OpenHouse 自动生成且可重建的 service spec。

不得清理：

- 用户工作目录，例如 `/root/workspace`。
- 用户模型配置。
- 用户输入的 key/token。
- `/root/.claude`、`/root/.codex` 中用户认证数据，除非用户明确选择重置模型配置。
- `/root/.cloudcli` 中用户数据库，除非用户明确选择重置 CloudCLI。
- `/root/openhouse/docs` 中用户新增的笔记。
- 历史日志。
- service-manager auth token。
- Termux 用户 home 下非 OpenHouse 管理的文件。
- Ubuntu rootfs 本身，除非用户明确选择重装 Ubuntu。

强制重试必须在日志中记录：

```text
stage_id
retry_mode=force-current-stage
cleaned_paths
preserved_paths
reason
```

`cleaned_paths` 不能包含 secret 值，路径中如含用户标识应脱敏。

## 健康检查要求

关键健康检查：

| 检查对象 | 检查方式 |
| --- | --- |
| Termux 基础环境 | `smallphoneai-env-probe` 或等价探测输出。 |
| Ubuntu | `proot-distro login ubuntu -- true`。 |
| Node | Ubuntu 内 `node --version`、`npm --version`。 |
| 文档 | `/root/openhouse/docs` 存在且 P0 文档可读。 |
| service-manager | OpenHouse 专用配置中的 `listen_addr` / `base_url` 对应 API 可访问；endpoint 应从配置/env/default 解析，默认 fallback 通常是 `http://127.0.0.1:20087`，但端口不能写死。 |
| pi-agent | service-manager 状态和本地端口健康检查一致。 |
| cloudcli | 目标端口 `23083` 可访问，未配置模型时返回可解释页面或状态。 |
| smallphone | service-manager 状态和本地健康端口一致。 |
| final health | stdout 最后一个 JSON 可解析，且关键服务状态一致。 |

## 日志要求

日志分三层：

1. 用户摘要：安装页展示，短句、可理解、无敏感信息。
2. 详细日志：分阶段保存，用于用户复制给 AI 排障。
3. 原始调试日志：仅本机保存，导出前必须脱敏。

日志必须包含：

- 阶段开始和结束时间。
- 命令退出码。
- 失败摘要。
- 健康检查结果。
- 下载 URL 的域名和文件名。
- sha256 校验结果。

日志不得包含：

- 完整 API key。
- 完整 token。
- Authorization header。
- service-manager auth token。
- provider secret。
- 用户输入的完整模型凭据。
- 未脱敏的 service JSON `env` 字段。

脱敏格式：

```text
sk-****abcd
token=****abcd
Authorization: Bearer ****abcd
```

## 版本和制品校验

安装链路必须固定并记录：

- Termux bootstrap 脚本版本。
- Ubuntu rootfs 版本和 URL。
- Node 版本。
- service-manager payload 版本和 sha256。
- pi-agent payload 版本和 sha256。
- pi-web payload 版本和 sha256。
- openhouse-connect payload 版本和 sha256。
- smallphone payload 版本和 sha256。
- cloudcli 安装方式、版本和可执行路径。
- 可选 cc-switch arm64 二进制版本和 sha256。

任何 payload sha256 不匹配都必须失败，不允许继续。

## 最终 E2E 验收

一次完整真机验收必须证明：

1. 干净安装 APK 后可以完成首次安装。
2. 安装中断后可以常规重试恢复。
3. 网络失败后可以国内网络重试恢复。
4. `/root/openhouse/docs` 完整可读。
5. service-manager 可访问。
6. `smallphone`、`pi-agent`、`cloudcli` 被注册为稳定 ID，且无同名随机重复服务。
7. App 前台时核心服务自动保持运行。
8. pi-agent 可引导用户配置模型。
9. Claude 或 Codex 至少一个能真实发送消息并收到回复。
10. UI 状态、service-manager 状态、端口健康一致。
11. 日志不泄露 key/token。
12. 点击“停止运行栈”后核心服务停止，App 界面保留，本次会话自动保活暂停。
13. 点击“全部退出 OpenHouse”后核心服务停止，OpenHouse 界面关闭，并请求关闭 Termux 前台服务和终端会话；重新打开 App 后可按前台策略恢复。

只有上述全部通过，才能认为首次安装链路达到“彻底可用”的最低标准。
