# OpenHouse Core Stack 使用指南

本组合用于首次安装和在线补齐。先完成 Termux 的两段基础环境初始化，再由 Android 投递 APK 内置单包。

安装顺序：

1. 导入 Android 已投递 TAR 中实际可用的资源，不按资源数量拒绝。
2. 读取生产市场集合，下载本机缺失、版本不同或 SHA 不同的资源；每个市场归档只在下载后校验一次大小和 SHA-256。
3. 安装静态内容和固定脚本，不在此阶段启动或验证服务。
4. 内容安装完成后，单独执行 `wuxianpi-setup activate`。
5. 激活阶段必须通过带 Token 的服务列表、registry 和 WuxianPi 健康检查。
6. 确认 Android 私有存储已有 service-manager 连接后，最后单独安装 Ubuntu；Ubuntu 使用市场发布的 bootstrap、20/30 阶段与镜像/重试脚本，不依赖 APK 旧资源目录，失败不影响已经完成的核心环境。

市场不可用时继续使用 APK 已投递资源。完整 APK 可以离线安装；不完整 APK 保留已导入内容并明确报告缺失资源。
