# OpenHouseAI 开源说明

本仓库是基于 Termux app 的 OpenHouseAI fork。

## 许可证

上游 Termux app 使用 GPLv3-only。OpenHouseAI 在本仓库中的修改也按 GPLv3-only 发布，以保持组合项目的许可证兼容。

相关文件：
- [LICENSE.md](../LICENSE.md)
- [termux-shared/LICENSE.md](../termux-shared/LICENSE.md)

## 非官方 Termux

本项目不是 Termux 官方发布版本。不要把 OpenHouseAI APK 描述为官方 Termux 构建。

对外 Debug 分发使用仓库内固定测试签名，并以 GitHub Release 与官方网站为唯一可信下载渠道；不要安装其他来源的同包名 APK。
Debug 分发构建会在发布前校验 APK 的 SHA-256、包名、ABI、签名证书、Runtime 载荷和 `debuggable` 标志。

## 不应提交的内容

不要提交：
- `local.properties`
- APK 输出
- Gradle 构建输出
- 私有签名密钥
- 真实 API key 或 provider token
- 本地维护日志
- 设备特定配置

## 构建

```bash
./gradlew :app:assembleWithOperitDebug -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:assembleWithoutOperitDebug -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
```

`withOperit` 包含完整 Android 侧 Operit feature/module 和宿主桥接；`withoutOperit` 不依赖、不暴露 Operit。

## 安装到 ADB 设备

选择一个 flavor 安装：

```bash
adb install -r app/build/outputs/apk/withOperit/debug/termux-app_apt-android-7-withOperit-debug_universal.apk
adb install -r app/build/outputs/apk/withoutOperit/debug/termux-app_apt-android-7-withoutOperit-debug_universal.apk
```

两个 APK 的包名都是 `com.termux`，不能共存；它们只能在同一签名且 `versionCode` 单调递增时互相升级或替换。

## 发布检查

发布前：
1. 运行 `git status --short`。
2. 确认没有真实密钥。
3. 构建 APK。
4. 记录 APK SHA256。
5. 安装到真机。
6. 打开维护中心。
7. 确认一键阶段只包含 OpenHouseAI 范围。
8. 确认在线维护源可加载。
9. 确认 Codex CLI、Claude Code、CloudCLI、service-manager、pi 和 pi-web 阶段存在。
10. 仅在明确需要公开时上传 APK。
