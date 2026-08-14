# OpenHouse Core Stack 使用指南

本组合用于联网首次安装。先完成 Termux 的两段基础环境初始化，再安装本组合中的资源。

安装顺序：

1. 获取并安装最新 `openhouse-resource-manager`。
2. 下载本机缺失或版本不同的资源；每个归档只在下载后校验一次大小和 SHA-256。
3. 安装静态内容和固定脚本，不在此阶段启动或验证服务。
4. 内容安装完成后，单独执行 `wuxianpi-setup activate`。
5. 激活阶段必须通过带 Token 的服务列表、registry 和 WuxianPi 健康检查。
6. 确认 Android 私有存储已有 service-manager 连接后，最后单独安装 Ubuntu；Ubuntu 使用市场发布的 bootstrap、20/30 阶段与镜像/重试脚本，不依赖 APK 旧资源目录，失败不影响已经完成的核心环境。

市场不可用时，首次安装插件可以回退到 APK 内置离线总包。回退只用于离线安装，不会替换市场中的独立资源版本。
