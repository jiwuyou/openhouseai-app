# WuxianPi 首次安装

`wuxianpi.first-install 1.0.16` 将权限准备、APK 投递、静态资源安装、运行激活和 Ubuntu 安装拆成独立阶段。后续阶段失败不会删除已经完成的前序阶段。

第一段只安装 `tmux` 和 `libncursesw`。tmux 就绪后，第二段在持久会话内安装并验证完整 Termux 基础环境。只有第二段成功，APK 才开放短生命周期 HTTP 下载，让 Termux 获取单一 canonical TAR 并导入五个离线核心资源。首次安装不依赖资源更新插件。

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

3. 用户明确表示已经执行并返回后，再用无副作用命令验证 `RUN_COMMAND`。

不请求 SAF，也不通过 SAF 读取或修改 Termux Home。卡片出现时工作流必须暂停；“系统页面已打开”不代表权限、重载或探针已经成功。

## 三个独立阶段

首次安装的核心资源集合仍然固定为五个资源：service-manager、openhouse-control-plane、openhouse-runtime、wuyou 和 openhouse-web。

### 1. Delivery

Android 临时开放 canonical `openhouse-install-bundle.tar`；Termux 下载到 Inbox，下载成功后创建 `.ready`。这一阶段不启动服务、不读取 token，也不注册组件。

### 2. Content

`openhouse-resource-import` 检查 TAR 路径安全，然后调用 `openhouse-resource-manager` 完成五资源静态安装和 `current` 指针切换：

```text
service-manager
openhouse-control-plane
openhouse-runtime
wuyou
openhouse-web
```

Content 管理器不得访问 20087/20765，不得执行 `service-daemon`、`sv up` 或 registry API。service-manager 安装必须显式使用：

```sh
CONFIG_PATH="$HOME/.config/openhouseai/service-manager/config.json"
BIND="127.0.0.1:20087"
INSTALL_SERVICE=0
```

资源安装失败才回滚 `current`，失败候选和诊断保存在 `resource-manager/failed/`。运行失败绝不回滚 content。

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

All-in-One 与 Native 使用同一份 TAR。Ubuntu 在核心 offer 完成后单独安装，失败时只保留为待重试状态。运行中枢固定启动链路和首次安装均不依赖资源更新插件。
