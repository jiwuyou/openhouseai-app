# WuxianPi 首次安装

`wuxianpi.first-install 1.0.9` 负责宿主权限、Termux/Ubuntu 基础环境、运行中枢固定入口和桌面组件注册。Runtime/Web 等通用资源仍可由 `wuxianpi.resource-update 2.0.0` 处理，但运行中枢启动链路不依赖资源更新器。

首次安装入口应先刷新维修助手市场并运行已 promote 的最新 `wuxianpi.first-install`。工作流执行过程中还会再次读取市场最新的 `wuxianpi.resource-update` 脚本，因此首次安装插件和资源更新器分别升级，不依赖 APK 重新发版。

核心资源集合包含：

```text
service-manager
openhouse-control-plane
openhouse-runtime
wuyou
openhouse-web
```

首次安装会检查全部资源。当前安装、下载缓存或 APK 内置资源与目标 SHA-256 相同时不会联网下载；只有缺失、损坏或版本不同的资源才从维修助手市场获取。

## 执行顺序

1. 检查宿主类型和真实安装状态。
2. 准备 All-in-One 内部 Termux，或为 Native 获取 Termux Home SAF 与 RUN_COMMAND 权限。
3. 安装 tmux、curl、jq、tar、gzip、flock 和 Node 等基础能力。
4. 直接安装 `$PREFIX/bin/openhouse-control-plane-start` 和 `$PREFIX/libexec/openhouse/start-service-manager.sh`。
5. 从维修助手市场读取最新通用资源更新脚本并写入 Termux Home。
6. 运行宿主返回的原始 `wuxianpi-setup` 命令，安装系统依赖、Ubuntu 和 service-manager。
7. 通过固定入口启动 service-manager；脚本不读取 token、资源版本或 registry。
8. 验证资源、service-manager health、带 token 的服务列表和 registry。
9. 注册 `yuanshengwuxianpi` 桌面组件。

All-in-One 与 Native 使用相同资源 ID、版本和 SHA-256。市场不可用时使用 APK 内置集合。本机资源集合 sequence 高于 APK 时拒绝自动降级。

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
