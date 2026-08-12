# WuxianPi 首次安装

`wuxianpi.first-install 1.0.11` 将权限准备、APK 投递、静态资源安装和运行激活拆成独立阶段。运行激活失败不会删除已经校验并安装的资源，也不会要求重新投递约 38 MiB 的 APK 总包。

## Native 权限顺序

Termux 运行中枢入口固定为 `$PREFIX/bin/openhouse-control-plane-start`，它只转发到 `$PREFIX/libexec/openhouse/start-service-manager.sh`。

外部 Termux 必须严格按以下顺序处理：

1. 获取 Termux Home SAF。
2. 获取 Android `RUN_COMMAND` 权限；此时只授权，不执行命令探针。
3. 通过 SAF 读取 `$HOME/.termux/termux.properties`。
4. 将注释、`false` 或重复的配置规范为唯一一行：

   ```properties
   allow-external-apps = true
   ```

5. 提示用户打开 Termux，执行：

   ```sh
   termux-reload-settings
   ```

6. 用户返回后，再用无副作用命令验证 `RUN_COMMAND`。

重载设置是明确的人工步骤。维修助手不能在配置尚未生效时反复探测或绕过权限。

## 三个独立阶段

首次安装的核心资源集合仍然固定为五个资源：service-manager、openhouse-control-plane、openhouse-runtime、wuyou 和 openhouse-web。

### 1. Delivery

Android 只把 canonical `openhouse-install-bundle.tar` 写入 Termux Inbox，重新读取校验大小和 SHA-256，最后创建 `.ready`。这一阶段不启动服务、不读取 token，也不注册组件。

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

All-in-One 与 Native 使用同一份 TAR。首次安装和运行中枢启动链路都不依赖该插件 `wuxianpi.resource-update`；后续在线更新仍只处理静态 content。
