# APK 随附更新资源

当前 APK 内置 `openhouse-core-stack` 作为首次安装和离线恢复种子。普通 APK 更新不再使用它升级 WuxianPi 或 Termux 运行资源；维修助手市场的 `wuxianpi.resource-update` 只确认或修复 Android 私有的 service-manager 连接。

资源集合固定包含 `service-manager`、`openhouse-control-plane`、`openhouse-runtime`、`wuyou` 和 `openhouse-web`。更新器先验证已安装凭据和文件树，再依次复用本地归档缓存、APK 内置 TGZ，最后才从市场下载。

当前版本目录是该 APK 的完整资源副本，供首次安装和显式离线恢复使用：

- `bootstrap/`：首次安装 bootstrap 与配套脚本；
- `maintainer/`：Android 维护脚本；
- `scripts-public/`：可公开调用的检查与后置安装脚本；
- `product-payloads/`：`resource-set.json`、五个 canonical TGZ 及旧 bootstrap 所需 manifest；
- `AI_UPDATE_GUIDE.md`：本说明；
- `.complete`：APK 版本、完整投递标志，以及 bootstrap、maintainer、scripts-public 中脚本文件的 size/SHA-256 清单。任何维护脚本执行前都会重算清单；脚本被截断、替换或变成符号链接时不会执行。

Android 在首次安装时从这个目录运行既有安装流程。普通 APK 更新流程不得根据这里的 `resource-set.json` 更新、替换、解包、启动或停止 Termux 中的组件，也不得调用旧的资源 `plan/apply/verify/rollback` 流程。

APK 更新后请进入维修助手运行“APK 配套更新”。它只检查 Android 私有连接；WuxianPi、Runtime、Web 和其他 Termux 内容由各自的运行更新流程处理。

旧目录可能仍保留 `PENDING_APK_RESOURCES.json`，但普通 APK 配套更新不再消费这个旧标记或其中的资源。

每个 APK 只保留一个 Android 命名的 `apk-*` 完整版本目录。新版本成功投递后会清理旧的 `apk-*` 目录，不会删除用户或 AI 创建的 `minor-*` 增量资源目录。
