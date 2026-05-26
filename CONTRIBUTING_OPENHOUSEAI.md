# OpenHouseAI 贡献说明

变更应保持范围清晰、便于审查。

## 开发规则

- 除非 OpenHouseAI 功能明确需要，不要改变上游 Termux 行为。
- OpenHouseAI 代码和资源命名应保持清楚。
- 不要硬编码 API key、登录 token 或模型服务凭据。
- 阶段文字、脚本流和在线维护清单优先通过动态维护源更新。
- 提交代码前运行 debug 构建。

## 常用命令

```bash
git status --short
./gradlew :app:assembleDebug -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
adb install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk
```
