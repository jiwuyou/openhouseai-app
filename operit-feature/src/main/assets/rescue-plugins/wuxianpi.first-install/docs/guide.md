# WuxianPi 首次安装

`wuxianpi.first-install 1.0.14` 将权限准备、APK 投递、静态资源安装和运行激活拆成独立阶段。运行激活失败不会删除已经安装的资源，也不会要求重新投递 APK 总包。

持久 Termux 准备完成后，首次安装会先更新基础包，并确认 `jq`、`curl`、`tar`、`gzip`、`tmux`、`sv` 和 `service-daemon` 可用。APK 离线内容导入后，联网时安装并复用 `wuxianpi.resource-update 3.0.0` 收敛到市场中最新的兼容资源集合；五个资源版本相同时不会重复下载。市场不可用时直接使用 APK 集合继续激活。

## Native 权限顺序

Termux 运行中枢入口固定为 `$PREFIX/bin/openhouse-control-plane-start`，它只转发到 `$PREFIX/libexec/openhouse/start-service-manager.sh`。

外部 Termux 必须严格按以下顺序处理：

1. 调用 SAF 检查工具。未授权或旧授权失效时，只在对话中展示说明卡片，不自动打开系统页面；用户点击卡片后才打开目录选择器，返回后再次检查真实读写状态。
2. 调用 Android `RUN_COMMAND` 权限检查工具。未授权时只展示卡片；用户点击后才申请权限，返回后再次检查。此时不得执行命令探针。
3. 通过 SAF 读取 `$HOME/.termux/termux.properties`。
4. 将注释、`false` 或重复的配置规范为唯一一行：

   ```properties
   allow-external-apps = true
   ```

5. 配置写入并验证成功后展示 reload 卡片。用户点击卡片打开 Termux，并手工执行：

   ```sh
   termux-reload-settings
   ```

6. 用户明确表示已经执行并返回后，再用无副作用命令验证 `RUN_COMMAND`。

检查工具、卡片启动动作和最终验证是三个不同阶段。卡片出现时工作流必须暂停；“系统页面已打开”不代表授权、重载或探针已经成功。重载设置是明确的人工步骤，维修助手不能在用户确认前继续，也不能在配置尚未生效时反复探测或绕过权限。

## 三个独立阶段

首次安装的核心资源集合仍然固定为五个资源：service-manager、openhouse-control-plane、openhouse-runtime、wuyou 和 openhouse-web。

### 1. Delivery

Android 只把 canonical `openhouse-install-bundle.tar` 写入 Termux Inbox，确认写入字节数和最终文件大小，最后创建 `.ready`。这一阶段不启动服务、不读取 token，也不注册组件。

### 2. Content

`openhouse-resource-import` 校验 TAR 安全性及其中五个资源，然后调用 `openhouse-resource-manager` 完成静态安装和 `current` 指针切换：

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

失败写入具体 `activationFailure`，例如 `canonical_auth_failed`、`registry_sync_failed` 或 `wuxianpi_health_failed`。日志只输出判定结果，不输出 token。

## APK offer 完成

资源导入后 offer 保持：

```text
delivery=ready
content=installed
activation=pending
status=pending
```

只有 `get_wuxianpi_setup_status` 返回匹配的 `offerId`、`resourceSetSequence`，且 canonical auth、服务列表、runit、registry 和 WuxianPi health 全部通过后，才调用 `complete_apk_resource_offer` 标记 `satisfied`。失败或忽略不能伪造完成。

All-in-One 与 Native 使用同一份 TAR。离线 TAR 负责保底，在线首次安装和后续更新复用同一个 `wuxianpi.resource-update 3.0.0` 差异收敛流程。运行中枢固定启动链路仍不依赖资源更新插件。
