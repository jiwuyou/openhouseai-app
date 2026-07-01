# OpenHouseAI 开源说明

本仓库是基于 Termux app 的 OpenHouseAI fork。

## 许可证

上游 Termux app 使用 GPLv3-only。OpenHouseAI 在本仓库中的修改也按 GPLv3-only 发布，以保持组合项目的许可证兼容。

相关文件：
- [LICENSE.md](../LICENSE.md)
- [termux-shared/LICENSE.md](../termux-shared/LICENSE.md)

## 非官方 Termux

本项目不是 Termux 官方发布版本。不要把 OpenHouseAI APK 描述为官方 Termux 构建。

Debug APK 可能使用上游公开测试签名，只适合开发和测试，不应作为生产信任锚。

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
./gradlew :app:assembleDebug -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
```

## 安装到 ADB 设备

```bash
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
```

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
