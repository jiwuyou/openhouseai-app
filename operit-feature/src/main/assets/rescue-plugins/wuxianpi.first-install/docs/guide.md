# WuxianPi 首次安装

`wuxianpi.first-install 1.0.18` 将权限准备、市场静态资源安装、运行激活和 Ubuntu 安装拆成独立阶段。后续阶段失败不会删除已经完成的前序阶段。

第一段只按需安装 `tmux`，由 Termux 自动解析当前 `ncurses` 依赖。tmux 就绪后，第二段在持久会话内只安装并验证核心 Termux 基础环境和 Node.js 24，不执行 `pkg upgrade`。只有第二段成功，才查询市场 promoted 的 `openhouse-core-stack`，显示集合内嵌指南，并获取差异资源。市场不可用时才开放 APK 的短生命周期 HTTP 下载，导入五个离线核心资源。首次安装不依赖资源更新插件。

第二段核心包为：

```text
bash jq curl ca-certificates tar gzip zstd coreutils findutils gawk
util-linux procps termux-services nodejs-lts（失败时回退 nodejs）
```

其中 `termux-services` 提供 `sv`、`service-daemon` 和 `runsvdir`，`util-linux` 提供 `flock`，`zstd` 只用于解开当前 WuxianPi Runtime 内部的三个 `.tar.zst` 层。Node.js 主版本必须不低于 24 且 `npm` 可执行。`proot-distro` 只在最后 Ubuntu 阶段按需安装；不安装 `libncursesw`、`git`、Python 或编译工具链。

## Native 权限顺序

Termux 运行中枢入口固定为 `$PREFIX/bin/openhouse-control-plane-start`，它只转发到 `$PREFIX/libexec/openhouse/start-service-manager.sh`。

外部 Termux 必须严格按以下顺序处理：

1. 检查 Android `RUN_COMMAND` 权限。未授权时展示延迟卡片；只有用户点击后才打开权限页。
2. 展示固定的 Termux 配置命令，等待用户在 Termux 中完整执行：

   ```sh
   mkdir -p ~/.termux
   echo 'allow-external-apps = true' >> ~/.termux/termux.properties
   termux-reload-settings
   ```

3. 用户明确表示已经执行并返回后，再用 `execute_termux_command` 运行 `printf %s wuxianpi-termux-ready` 无副作用探针。判断时去除 stdout 首尾空白，避免末尾换行造成假阴性。

不请求 SAF，也不通过 SAF 读取或修改 Termux Home。卡片出现时工作流必须暂停；“系统页面已打开”不代表权限、重载或探针已经成功。

## 市场优先与离线回退

联网时资源集合包含 `service-manager`、`openhouse-runtime`、`wuyou`、`openhouse-web` 和 11 个单脚本资源。每个脚本归档只含一个可执行文件，安装位置由资源管理器内置允许列表决定。`_termux-services-env.sh`、50/60 脚本、固定启动入口、注册脚本和诊断脚本因此可以独立更新，不需要发布 APK。

市场步骤先下载 `openhouse-resource-manager.tgz`，校验大小和 SHA-256 后安装资源管理器；资源管理器先下载全部差异归档，再逐个安装并写 receipt。某个资源失败时保留已经成功的资源和失败证据，下次运行只继续未完成项。安装目录不做逐文件 Hash。

只有市场获取或静态安装失败时才使用 APK 内的 `openhouse-install-bundle.tar`。APK 总包维持原来的五资源离线兜底，不要求与更高 sequence 的市场集合相同。

## 三个独立阶段

### 1. Delivery

市场路径直接下载版本化 TGZ；离线回退时 Android 临时开放 canonical `openhouse-install-bundle.tar`，Termux 下载到 Inbox 并创建 `.ready`。这一阶段不启动服务、不读取 token，也不注册组件。

### 2. Content

市场路径由 `openhouse-resource-manager market` 安装完整集合；APK 回退路径由 `openhouse-resource-import` 检查 TAR 路径安全后安装五个离线资源。两条路径最终使用同一个本地资源目录和 receipt：

```text
service-manager
openhouse-runtime
wuyou
openhouse-web
11 个固定安装位置的脚本资源（仅市场）
```

Content 管理器不得访问 20087/20765，不得执行 `service-daemon`、`sv up` 或 registry API。service-manager 安装必须显式使用：

```sh
CONFIG_PATH="$HOME/.config/openhouseai/service-manager/config.json"
BIND="127.0.0.1:20087"
INSTALL_SERVICE=0
```

APK content 事务失败时回滚 `current`。市场资源逐个提交，失败候选和诊断保存在 `resource-manager/failed/`，已经提交的资源不回滚。运行失败绝不回滚 content。

### 3. Activation

`wuxianpi-setup activate` 独立执行并可重复运行：

```text
创建或验证 canonical 配置
→ 使用显式 --config/--bind 安装 runit service
→ 启动 runsvdir/service-manager
→ 带 canonical token 查询服务列表
→ 注册资源并同步 registry
→ 启动和验证 WuxianPi
```

激活前会创建 `$PREFIX/var/service`、`$PREFIX/var/log` 和 `$PREFIX/var/lock`，检查 `_termux-services-env.sh`、50/60 脚本、`$PREFIX/bin/openhouse-control-plane-start` 与 `$PREFIX/libexec/openhouse/start-service-manager.sh` 可执行，并确认它们使用 Termux Bash 绝对 shebang。组件注册必须通过 `registry/apply` 同时提交含 `ai` layer 的组件和 `yuanshengwuxianpi` 服务，再执行 `registry/sync`；只写入 `services.d` 不视为注册成功。

失败写入具体 `activationFailure`，例如 `canonical_auth_failed`、`registry_sync_failed` 或 `wuxianpi_health_failed`。成功后 Termux 通过 OpenHouse connection bridge 上报 service-manager URL 和 Token；OpenHouse 自身直接读取 Android 私有存储，不通过该 HTTP 端口。

## APK offer 完成

资源导入后 offer 保持：

```text
delivery=ready
content=installed
activation=pending
status=pending
```

只有 `get_wuxianpi_setup_status` 返回匹配的 `offerId`、`resourceSetSequence`，且 canonical auth、服务列表、runit、registry 和 WuxianPi health 全部通过后，才调用 `complete_apk_resource_offer` 标记 `satisfied`。失败或忽略不能伪造完成。

All-in-One 与 Native 的离线回退使用同一份 TAR。市场集合 sequence 更高时，APK offer 可以按宿主规则标记为已被更高集合取代。Ubuntu 在核心资源和激活完成后单独安装，失败时只保留为待重试状态。运行中枢固定启动链路和首次安装均不依赖资源更新插件。长任务应保留 `session_id`；需要读取末尾日志时使用 `tmux capture-pane -p -S -2000`，不能依赖普通输出缓冲区的完整回放。
