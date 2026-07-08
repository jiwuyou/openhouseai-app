# GitHub、网络检索和镜像

OpenHouse 的很多能力依赖外部开源项目和模型服务。AI 遇到安装、配置、API 格式、provider 差异或版本变化时，应主动检索，不要硬猜。

## 检索优先级

优先查：

1. 官方文档。
2. 官方 GitHub README。
3. release note。
4. issue 和 discussion。
5. 官方示例配置。
6. 维护者发布的迁移说明。

只有官方信息不足时，才参考博客、论坛、镜像站或第三方教程，并向用户说明可信度。

## 什么时候必须检索

以下情况应联网检索：

- 模型 provider 的 API 格式可能已经变化。
- CloudCLI / Claude Code / Codex / Hermes Web 的安装方式变化。
- DeepSeek、OpenRouter、Gemini、Anthropic、OpenAI 等 provider 的兼容协议不确定。
- GitHub clone、release 下载、npm、uv、pip 安装失败。
- WebView 或 Android 版本兼容性问题没有本地文档覆盖。
- 用户要求安装新的开源工作台。

## GitHub 访问慢或失败

可以依次尝试：

1. 换网络或等待。
2. 查询项目官方 release 下载地址。
3. 使用代理环境变量。
4. 使用 Tailscale 或用户提供的网络环境。
5. 使用 GitHub 镜像源。
6. 搜索包管理器是否提供同名包。
7. 请求用户提供压缩包或备用下载地址。

使用镜像时必须注意：

- 镜像可能滞后。
- 镜像可能被篡改。
- 镜像可能不包含 release asset。
- 下载后应尽量核对项目名、版本号、commit、hash 或官方 release 信息。

## 代理环境变量

如果用户明确提供代理，可以临时设置：

```bash
export HTTPS_PROXY=http://host:port
export HTTP_PROXY=http://host:port
export ALL_PROXY=socks5://host:port
```

不要把代理、token、cookie 写入仓库、APK 资源或公共文档。需要长期保存时，应写入用户私有配置，并说明位置。

## 镜像使用规则

AI 可以使用镜像，但需要遵守：

- 先尝试官方源或官方文档。
- 使用镜像前说明原因。
- 下载关键执行文件后尽量校验。
- 不能把镜像内容当成唯一真相。
- 如果镜像和官方说明冲突，以官方说明为准。

## 对用户的报告

网络问题排查结果应简洁说明：

- 官方源是否可达。
- 镜像是否使用。
- 下载的版本和来源。
- 是否校验。
- 还有什么风险。
