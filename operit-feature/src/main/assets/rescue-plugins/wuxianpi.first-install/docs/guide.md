# WuxianPi 首次安装

`wuxianpi.first-install 1.0.28` 将权限准备、Android 资源投递、市场差异补齐、运行激活和 Ubuntu 安装拆成独立阶段。技术流程结束后单独启动 `wuxianpi.setup-finish` 收尾插件。后续阶段失败不会删除已经完成的前序阶段。

第一段只按需安装 `tmux`，由 Termux 自动解析当前 `ncurses` 依赖。tmux 就绪后，第二段在持久会话内只安装并验证核心 Termux 基础环境和 Node.js 24，不执行 `pkg upgrade`。只有第二段成功，Android 宿主才从 APK Asset 读取 canonical TAR，通过短生命周期本机 HTTP 投递到 Termux Inbox。Termux 只解包已投递的 TAR，不读取 APK。随后查询市场 promoted 的 `openhouse-core-stack`，只获取缺失、版本变化或 SHA 变化的资源。首次安装不依赖资源更新插件。

第二段核心包为：

```text
bash jq curl ca-certificates tar gzip zstd coreutils findutils gawk
util-linux procps termux-services nodejs-lts（失败时回退 nodejs）
```

其中 `termux-services` 提供 `sv`、`service-daemon` 和 `runsvdir`，`util-linux` 提供 `flock`，`git` 用于导入和更新 WuxianPi Package，`zstd` 只用于解开当前 WuxianPi Runtime 内部的三个 `.tar.zst` 层。Node.js 主版本必须不低于 24 且 `npm` 可执行。`proot-distro` 只在最后 Ubuntu 阶段按需安装；不安装 `libncursesw`、Python 或编译工具链。

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

## 本地投递与市场补齐

联网时资源集合包含核心服务、单脚本资源，以及任意数量的 `wuxianpi-package-*` 发行包。每个脚本归档只含一个可执行文件，安装位置由资源管理器内置映射决定；Package 则统一投递到 `~/.local/share/openhouseai/distribution-packages/`，由 Runtime 动态 reconcile，不再从 npm 安装公版。新增 Package 只需发布符合通用协议的新资源，不需要修改首次安装工作流或重新发布 Runtime。

APK 新制品应携带当前 canonical 集合的全部资源，但运行时不按资源数量判定投递是否成功。导入器只安装 TAR 中实际存在且结构有效的资源；缺失或损坏的本地归档交给市场补齐。资源管理器按资源 ID、版本和 SHA 比较，只下载差异归档并逐个写 receipt。某个资源失败时保留已经成功的资源和失败证据，下次运行只继续未完成项。安装目录不做逐文件 Hash。

市场不可用时保留 Android 已投递并导入的内容。完整 APK 可以继续离线安装；不完整 APK 会明确报告缺失的必要资源，不回滚已经导入的内容，也不伪造 ready。

## 三个独立阶段

### 1. Delivery

Android 临时开放 canonical `openhouse-install-bundle.tar`，Termux 下载到 Inbox 并创建 `.ready`。市场只在本地导入后提供差异 TGZ。这一阶段不启动服务、不读取 token，也不注册组件。

### 2. Content

`openhouse-resource-import` 检查已投递 TAR 的路径安全后安装实际可用资源，随后 `openhouse-resource-manager market` 补齐市场差异。两条路径使用同一个本地资源目录、receipt 和合并后的 `installed-set.json`：

```text
核心程序资源
固定安装位置的脚本资源
Ubuntu 后置阶段脚本资源
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

激活前会创建 `$PREFIX/var/service`、`$PREFIX/var/log` 和 `$PREFIX/var/lock`，检查 `_termux-services-env.sh`、50/60 脚本、`$PREFIX/bin/openhouse-control-plane-start` 与 `$PREFIX/libexec/openhouse/start-service-manager.sh` 可执行，并确认它们使用 Termux Bash 绝对 shebang。组件注册必须通过 `registry/apply` 同时提交含 `ai` layer 的组件和 `yuanshengwuxianpi` 服务，再执行 `registry/sync`；只写入 `services.d` 不视为注册成功。激活后 Runtime 会读取发行目录的 `index.json`，动态导入全部 Package；工作流再逐项确认 `sourceKind=preinstalled`、`sourceStatus=ready`，不能只看 health 就结束。

失败写入具体 `activationFailure`，例如 `canonical_auth_failed`、`registry_sync_failed` 或 `wuxianpi_health_failed`。激活前，维修助手在工具可用时调用 `ensure_openhouse_connection_bridge`，并把返回的 `bridgeId` 传给 `wuxianpi-setup activate --connection-bridge-id`。成功后 Termux 通过匹配的 OpenHouse connection bridge 上报 service-manager URL 和 Token；OpenHouse 自身直接读取 Android 私有存储，不通过该 HTTP 端口。

Bridge 工具不受支持或端口无法监听时不阻塞核心激活。维修助手必须执行 `wuxianpi-setup connection-info`，然后把返回的 `serviceManagerBaseUrl` 和 `token` 传给 `write_service_manager_connection`，直接保存到 Android 私有存储；该恢复路径完全不经过 Bridge HTTP。无论走哪条路径，最终都必须由 `store_service_manager_connection` 确认 `hasToken=true`。连接未保存时不得进入 Ubuntu。

## APK offer 完成

资源导入后 offer 保持：

```text
delivery=ready
content=installed
activation=pending
status=pending
```

只有 `get_wuxianpi_setup_status` 返回匹配的 `offerId`、`resourceSetSequence`，且 canonical auth、服务列表、runit、registry 和 WuxianPi health 全部通过后，才调用 `complete_apk_resource_offer` 标记 `satisfied`。失败或忽略不能伪造完成。

All-in-One 与 Native 使用同一份 Android 投递 TAR。市场集合 sequence 更高时，APK offer 可以按宿主规则标记为已被更高集合取代。Ubuntu 仅在核心资源、激活和 Android 私有连接确认都完成后单独安装；它使用市场安装的 `bootstrap.sh`、20/30 阶段脚本与镜像/重试策略，不依赖 APK 旧资源目录。失败时只保留为待重试状态。运行中枢固定启动链路和首次安装均不依赖资源更新插件。长任务应保留 `session_id`；需要读取末尾日志时使用 `tmux capture-pane -p -S -2000`，不能依赖普通输出缓冲区的完整回放。
