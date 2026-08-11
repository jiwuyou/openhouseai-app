# WuxianPi 首次安装

`wuxianpi.first-install 1.0.10` 负责宿主权限、APK 离线总包导入、Termux/Ubuntu 基础环境、运行中枢固定入口和桌面组件注册。Runtime/Web 等通用资源后续仍可由 `wuxianpi.resource-update` 在线差异更新，但首次安装和运行中枢启动链路都不依赖该插件。

首次安装入口应先刷新维修助手市场并运行已 promote 的最新 `wuxianpi.first-install`。APK 内置 bootstrap seed 已包含本地资源管理器，可在没有 `wuxianpi.resource-update` 和没有网络时导入 APK 离线资源。

核心资源集合包含：

```text
service-manager
openhouse-control-plane
openhouse-runtime
wuyou
openhouse-web
```

首次安装将单一 `openhouse-install-bundle.tar` 投递到 Termux Inbox，再由 Termux 校验和安装。总包中的五个资源仍独立比较；当前安装与目标 SHA-256 相同时不会重复安装。

## 执行顺序

1. 检查宿主类型和真实安装状态。
2. 准备 All-in-One 内部 Termux，或为 Native 获取 Termux Home SAF 与 RUN_COMMAND 权限。
3. 安装 tmux、curl、jq、tar、gzip、flock 和 Node 等基础能力。
4. 直接安装 `$PREFIX/bin/openhouse-control-plane-start` 和 `$PREFIX/libexec/openhouse/start-service-manager.sh`。
5. 将 canonical TAR 写入 `apk-resource-inbox/<offerId>/`，校验后最后创建 `.ready`。
6. 运行宿主返回的 `wuxianpi-setup --resource-inbox ...`，由 Termux 导入器完成五资源事务安装并安装 Ubuntu。
7. 通过固定入口启动 service-manager；脚本不读取 token、资源版本或 registry。
8. 验证资源、service-manager health、带 token 的服务列表和 registry。
9. 注册 `yuanshengwuxianpi` 桌面组件。

All-in-One 与 Native 使用同一份 TAR、资源 ID、版本和 SHA-256。Native SAF 只使用创建、写入、读取和删除，不要求 `renameDocument`；本机资源集合 sequence 高于 APK 时拒绝自动降级。

## Android-Termux 控制面

Android 只调用固定入口：

```text
$PREFIX/bin/openhouse-control-plane-start
  -> $PREFIX/libexec/openhouse/start-service-manager.sh
```

第二层脚本只设置 `SVDIR/LOGDIR`、获取 `flock`、启动 `service-daemon`、等待 `runsvdir` 并重试 `sv up service-manager`。它不安装服务、不读取 token、不访问 API。Android 在命令结束后分别验证 health 和带 token 的服务列表。

## 桌面组件

服务 ID、组件 ID 和文件名统一为：

```text
yuanshengwuxianpi
components.d/yuanshengwuxianpi.json
```

注册接口：

```text
PUT /api/v1/registry/components/yuanshengwuxianpi
POST /api/v1/registry/sync
```

新版宿主返回桌面时会刷新组件，不需要强制停止 App。重复执行首次安装和资源收敛都是幂等的，不能删除用户配置、模型、会话或自定义组件。
