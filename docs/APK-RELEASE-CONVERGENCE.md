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

## 需要收敛的源码位置

- `app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup`
- `app/src/main/assets/smallphoneai/bootstrap/scripts/50-install-runtime-components.sh`
- `app/src/main/assets/smallphoneai/bootstrap/scripts/48-sync-openhouse-registry.sh`
- `app/src/main/assets/smallphoneai/bootstrap/components.d/yuanshengwuxianpi.json`

“主体/系统目录同步”和“桌面组件 registry”必须是两个可独立重试的步骤；不得因为跳过 OpenHouse 系统目录而跳过 `yuanshengwuxianpi` 注册。Runtime payload ID 仍可使用 `pi-agent`，但桌面组件 ID 和 service-manager 服务 ID 必须统一为 `yuanshengwuxianpi`。

## 自动化门禁

- [ ] `scripts/validate-openhouse-payloads.sh` 检查 bootstrap 或 payload 中存在 `pi-agent` 清单。
- [ ] `app/src/test/shell/wuxianpi-runtime-contract-test.sh` 检查注册和 sync 逻辑、稳定服务 ID、幂等行为。
- [ ] 检查不会覆盖用户已有 `components.d` 文件。
- [ ] 检查首次安装后 registry API 可读取 `pi-agent`。
- [ ] 检查旧 `yuanshengwuxianpi` 服务仍能被升级和重跑安装复用。

## 验收场景

- [ ] All-in-One 首次安装后自动出现桌面入口。
- [ ] Native 首次安装后自动出现桌面入口。
- [ ] 已安装设备更新市场插件后可补齐入口。
- [ ] 重复运行不生成重复组件。
- [ ] WuxianPi 停止时仍可注册组件，打开时由入口按需启动或提示启动。
- [ ] 组件损坏后重跑安装可以修复。
- [ ] 原生 App 重启后桌面和侧边栏均显示 WuxianPi AI。
- [ ] APK 版本、签名和既有载荷不因本收敛清单改变。
