# APK 发布收敛清单

本文记录正式 APK 发布需要永久收敛的安装契约。市场插件必须继续保留，作为旧 APK 的兼容修复路径。

## 产品与发布基线

- 正式开发和发布基线固定为 `feature/wuxianpi-ai-web-ui`。
- `feature/operit-lean-host` 只保留为本次整合历史，不再直接用于对外构建。
- 正式构建必须来自与 `origin/feature/wuxianpi-ai-web-ui` 相同的干净提交。
- 基线必须包含 `a8844dd1` 和 `fd30e815`。
- `scripts/validate-product-baseline.sh` 校验提交、工作区和 lean 设置页契约。
- `scripts/report-apk-build.sh` 记录 commit、APK 路径、大小、SHA-256、ABI、签名证书和资产检查。

## Debug 分发基线

- [ ] `scripts/build-all-in-one.sh` 默认执行 `:app:assembleDebug`。
- [ ] `scripts/build-native.sh` 默认执行 `:native-app:assembleDebug`。
- [ ] All-in-One 默认保留 `arm64-v8a` 和 `universal`，Native 默认仅保留 `arm64-v8a`。
- [ ] Debug APK 必须由 `app/testkey_untrusted.jks` 签名，并保持包名和签名连续性。
- [ ] Debug APK 必须由校验器确认 `application-debuggable`、APK v2 签名、ZIP 对齐、Runtime SHA-256 和必需资产。
- [ ] 每个 APK 生成同名 `.txt` 和 `.json` 构建报告，包含 commit、版本、大小、SHA-256、ABI 和证书指纹。
- [ ] GitHub Debug 构建同时产出 All-in-One 和 Native；Release 上传流程不得只构建 All-in-One。
- [ ] Release 构建仍可通过 `ALL_IN_ONE_GRADLE_TASK=:app:assembleRelease` 或 `NATIVE_GRADLE_TASK=:native-app:assembleRelease` 显式调用。

## Native WuxianPi 桌面组件注册

- [ ] 在 APK bootstrap 中内置标准 `components.d/yuanshengwuxianpi.json` 模板。
- [ ] `yuanshengwuxianpi` 安装完成后自动执行桌面组件注册。
- [ ] 注册步骤调用 canonical service-manager 的 registry apply/PUT 和 sync API。
- [ ] registry PUT 路径使用 `/api/v1/registry/components/yuanshengwuxianpi`。
- [ ] `GET /api/v1/registry/components` 返回 `yuanshengwuxianpi`。
- [ ] 原生桌面重启后显示并可打开 WuxianPi AI。
- [ ] 重跑安装保持幂等，只更新 `yuanshengwuxianpi`。
- [ ] 仅迁移确认属于 WuxianPi 的旧 `pi-agent.json`，不删除用户自定义组件。
- [ ] 不删除用户已有的 `components.d/*.json` 或 service-manager 服务。
- [ ] `SMALLPHONEAI_SKIP_OPENHOUSE_SYSTEM=1` 不能同时跳过桌面组件注册。
- [ ] 保留 `wuxianpi.first-install` 市场插件作为旧 APK 的补救路径。

## Android-Termux 控制面

- [ ] All-in-One 与 Native 都只执行 `$PREFIX/bin/openhouse-control-plane-start`，不传启动参数。
- [ ] 固定入口只 `exec $PREFIX/libexec/openhouse/start-service-manager.sh` 并透传 stdout、stderr 和 exitCode。
- [ ] 第二层脚本只设置 `SVDIR/LOGDIR`、获取 `flock`、启动 `service-daemon`、等待 `runsvdir` 并重试 `sv up service-manager`。
- [ ] 第二层脚本不得读取 token、访问 API、安装/覆盖服务、检查 APK 资源、修改配置或同步 registry。
- [ ] 两个脚本由 `wuxianpi.first-install` 直接安装，由 `wuxianpi.termux-repair` 在用户明确要求时补齐。
- [ ] 通用资源更新器不得拥有、校验、覆盖或阻塞两个固定脚本。
- [ ] Android 前台只用无鉴权 `/api/v1/health` 每 15 秒探测；后台停止探测但不停止 service-manager。
- [ ] 手动页面仅在命令 exitCode 为 0、health 成功且带 token 的服务列表成功后报告启动成功。
- [ ] 手动和自动启动共享进程级单飞协调器；Termux 使用 `$PREFIX/var/lock/openhouse-control-plane-start.lock` 防止跨宿主并发。
- [ ] Android 页面展示最近启动命令的 stdout、stderr、触发来源和 exitCode，日志不得输出 token。

## V2 核心资源集合

- [ ] `openhouse-core-stack` 可以继续分发既有通用资源，但运行中枢固定入口不依赖其中的 `openhouse-control-plane` 资源。
- [ ] All-in-One 与 Native 的五个 TGZ、resource-set version、sequence 和 SHA-256 完全一致。
- [ ] Native 通过 SAF 投递到 `update-resources/apk-*/product-payloads`，所有文件完成后才写 `.complete`。
- [ ] APK 更新只写 pending；APK 配套更新插件只确认或修复 Android 私有 service-manager 连接。
- [ ] APK 配套更新不得下载、切换、激活或回滚 WuxianPi/Termux 运行资源。
- [ ] 同版本内容损坏时按 SHA 和文件树凭据恢复，不能仅比较版本号。
- [ ] 本机 sequence 高于 APK 时禁止自动降级；显式 rollback 只回到 previous-set。
- [ ] APK 不再包含 Native 专用的第二份 `openhouse-runtime/runtime-aarch64.tgz`。

## 需要收敛的源码位置

- `app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup`
- `app/src/main/assets/smallphoneai/bootstrap/scripts/50-install-runtime-components.sh`
- `app/src/main/assets/smallphoneai/bootstrap/scripts/48-sync-openhouse-registry.sh`
- `app/src/main/assets/smallphoneai/bootstrap/components.d/yuanshengwuxianpi.json`
- `openhouse-core/.../ControlPlaneBridge.java`
- `openhouse-feature/.../ControlPlaneForegroundSupervisor.java`
- `termux-host-adapter/.../TermuxOpenHouseHost.java`
- `native-host-adapter/.../NativeControlPlaneBridge.kt`
- `native-app/src/main/assets/openhouse-resources-v2/`
- `native-host-adapter/src/main/java/com/openhouse/host/nativeapp/NativeTermuxHomeRepository.kt`
- `native-host-adapter/src/main/java/com/openhouse/host/nativeapp/NativeOpenHouseHost.java`

“主体/系统目录同步”和“桌面组件 registry”必须是两个可独立重试的步骤；不得因为跳过 OpenHouse 系统目录而跳过 `yuanshengwuxianpi` 注册。Runtime payload ID 仍可使用 `pi-agent`，但桌面组件 ID 和 service-manager 服务 ID 必须统一为 `yuanshengwuxianpi`。

## 自动化门禁

- [ ] `scripts/generate-resource-set-v2.sh --check` 校验五资源集合、发布 metadata 和两种 APK assets 同字节。
- [ ] `scripts/check-production-resource-alignment.sh` 校验生产活动集合、五个 latestVersion 及远端归档实物与 APK canonical 字节完全一致。
- [ ] 对齐期间资源目录 revision 或资源集合快照变化时构建失败并重试，不接受混合版本快照。
- [ ] `scripts/validate-openhouse-payloads.sh` 检查 bootstrap、五资源集合和 `yuanshengwuxianpi` 稳定服务契约。
- [ ] `app/src/test/shell/wuxianpi-runtime-contract-test.sh` 检查注册和 sync 逻辑、稳定服务 ID、幂等行为。
- [ ] 检查不会覆盖用户已有 `components.d` 文件。
- [ ] 检查首次安装后 registry API 可读取 `yuanshengwuxianpi`。
- [ ] 检查旧 `yuanshengwuxianpi` 服务仍能被升级和重跑安装复用。
- [ ] `start-control-plane-runit-race-test.sh` 校验固定入口、默认服务环境、runsvdir 等待和 `sv up` 重试。
- [ ] Native RUN_COMMAND 契约测试确认直接执行 `$PREFIX/bin/openhouse-control-plane-start`，不注入 APK asset stdin。

## 验收场景

- [ ] All-in-One 首次安装后自动出现桌面入口。
- [ ] Native 首次安装后自动出现桌面入口。
- [ ] 已安装设备更新市场插件后可补齐入口。
- [ ] 重复运行不生成重复组件。
- [ ] WuxianPi 停止时仍可注册组件，打开时由入口按需启动或提示启动。
- [ ] 组件损坏后重跑安装可以修复。
- [ ] 原生 App 重启后桌面和侧边栏均显示 WuxianPi AI。
- [ ] APK 版本、签名和既有载荷不因本收敛清单改变。
- [ ] Native 的全新外部 Termux 安装没有预先存在 `.smallphoneai-bootstrap` 时，点击“启动运行中枢”仍可启动 20087。
- [ ] APK 资源目录或旧 `control-plane/current` 不存在时，固定入口仍可启动已安装的 service-manager。
- [ ] token 缺失或错误不阻止固定命令执行，但手动页面必须把带 token 的服务列表验证失败显示出来。
- [ ] App 前台停止 service-manager 后会自动拉起；退到后台后不再轮询或自动执行。
