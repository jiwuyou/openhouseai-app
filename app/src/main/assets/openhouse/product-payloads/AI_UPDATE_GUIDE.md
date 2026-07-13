# APK 随附更新资源

当前版本目录是该 APK 的统一资源副本，首次安装和后续更新使用同一份内容：

- `bootstrap/`：首次安装 bootstrap 与配套脚本；
- `maintainer/`：Android 维护脚本；
- `scripts-public/`：可公开调用的检查与后置安装脚本；
- `product-payloads/`：service-manager、pi-web 等离线 payload 及 manifest；
- `AI_UPDATE_GUIDE.md`：本说明；
- `.complete`：APK 版本、完整投递标志，以及 bootstrap、maintainer、scripts-public 中脚本文件的 size/SHA-256 清单。任何维护脚本执行前都会重算清单；脚本被截断、替换或变成符号链接时不会执行。

Android 在首次安装时只会从这个目录运行既有安装流程；APK 更新时只投递资源，不会自动安装、替换、解包、启动或停止 Termux 中的组件。

请先阅读 `product-payloads/manifest.json` 与 `product-payloads/payload-manifest.json`，检查当前 Termux 环境、现有版本、用户配置和运行状态，再决定哪些资源适合更新。

可直接发送给 AI：

> 请查看 $HOME/.local/share/openhouseai/update-resources 中日期最新的资源目录，阅读其中的 manifest 和 AI_UPDATE_GUIDE.md，检查当前环境后完成合适的更新。

资源投递后，根目录会保留 `PENDING_APK_RESOURCES.json`：`reason=first_install` 表示首次安装正在消费该目录，首次安装成功后 Android 只会删除版本号与原因都匹配的标记；`reason=apk_update` 表示需要 AI 检查更新，AI 完成合适的迁移并验证结果后应删除该文件。

每个 APK 只保留一个 Android 命名的 `apk-*` 完整版本目录。新版本成功投递后会清理旧的 `apk-*` 目录，不会删除用户或 AI 创建的 `minor-*` 增量资源目录。
