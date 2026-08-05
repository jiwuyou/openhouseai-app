# APK 发布收敛清单

本文记录下一次正式 APK 发布需要永久收敛的安装契约。当前版本不修改 APK；WuxianPi 桌面入口由 `wuxianpi.first-install` 市场插件注册，公开说明见 `openhouse-docs`。市场插件必须继续保留，作为旧 APK 的兼容修复路径。

## Native WuxianPi 桌面组件注册

- [ ] 在 APK bootstrap 中内置标准 `components.d/pi-agent.json` 模板。
- [ ] `yuanshengwuxianpi` 安装完成后自动执行桌面组件注册。
- [ ] 注册步骤调用 canonical service-manager 的 registry apply/PUT 和 sync API。
- [ ] `GET /api/v1/registry/components` 返回 `pi-agent`。
- [ ] 原生桌面重启后显示并可打开 WuxianPi AI。
- [ ] 重跑安装保持幂等，只更新 `pi-agent`。
- [ ] 不删除用户已有的 `components.d/*.json` 或 service-manager 服务。
- [ ] `SMALLPHONEAI_SKIP_OPENHOUSE_SYSTEM=1` 不能同时跳过桌面组件注册。
- [ ] 保留 `wuxianpi.first-install` 市场插件作为旧 APK 的补救路径。

## 需要收敛的源码位置

- `app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup`
- `app/src/main/assets/smallphoneai/bootstrap/scripts/50-install-runtime-components.sh`
- `app/src/main/assets/smallphoneai/bootstrap/scripts/48-sync-openhouse-registry.sh`
- `app/src/main/assets/smallphoneai/bootstrap/components.d/pi-agent.json`

“主体/系统目录同步”和“桌面组件 registry”必须是两个可独立重试的步骤；不得因为跳过 OpenHouse 系统目录而跳过 `pi-agent` 注册。

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
