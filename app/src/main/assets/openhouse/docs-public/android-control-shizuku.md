# Android 控制和 Shizuku

这是稳定小写入口。完整说明见 `ANDROID_CONTROL_SHIZUKU.md`。

OpenHouse 可以在用户授权后探索 Android 侧增强能力，例如通过 Shizuku 调用部分系统能力或操控真机。此类能力必须显式授权、可见、可关闭，并在执行高风险操作前向用户说明影响。

如果 Shizuku 不可用，不应影响首装控制平面。先让 pi-agent、pi-web、service-manager 可用，再按用户目标配置 Android 控制能力。
