# Rescue Session Runtime

维修助手的对话启动与业务执行分为两个不可互相替代的角色。宿主始终从 APK 本地的核心插件开始，因此离线时仍可以进入普通维修对话。

```text
new conversation
  -> local session-bootstrap (once)
  -> frozen memory snapshot
  -> market refresh and transactional official-plugin update
  -> activate complete plugin set
  -> latest session-runtime (same conversation)
  -> selected business plugins and normal chat work
  -> verified memory patch
```

## Bootstrap And Runtime

- `wuxianpi.session-bootstrap` 是 APK 内置核心插件。当前对话使用启动时读取的版本；市场更新得到的 Bootstrap 版本只在下一个新对话生效。
- `wuxianpi.session-runtime` 在完整插件集合原子切换后立即重新读取，因此更新版本可在当前对话接管任务。
- 每个 `conversationId` 持久化一个单调阶段状态。`SESSION_RUNTIME` 之后拒绝回到 `LOCAL_BOOTSTRAP`，避免更新提示词或工具调用形成循环。
- 联网更新只处理已安装且兼容的官方插件，以及两个必需 session 插件。未安装的业务插件仍由 Runtime 根据用户目的选择和安装。
- 任一下载、清单、SHA-256 或兼容性校验失败时，活动索引不会切换，当前对话继续使用上一套完整插件集合。

## Dynamic Actions

活动插件可以通过 `manifest.json` 贡献顶部 action。action 只能提供标题、图标、显示条件、依赖和提示词；点击 action 只创建或恢复普通维修对话并注入提示词，不能直接执行 shell 或扩大工具权限。

宿主内置的唯一故障入口是“检查维修助手市场”。其它 action 都来自当前活动插件集合，因此市场更新完成后会立即重新加载。

## Memory

Android 私有目录保存权威缓存、修订号、历史和 pending 同步标记。Termux 侧镜像使用：

```text
~/.local/share/openhouseai/memory/assistant-memory.md
~/.local/share/openhouseai/memory/device-state.json
~/.local/share/openhouseai/memory/memory-sync.json
```

新对话冻结记忆前会尽力合并 Termux 镜像；任务通过受控记忆工具写入后会尽力回推。同步失败不打断对话，pending 标记会在后续会话或下一次写入时重试。用户偏好和明确记忆必须获得确认；密码、Token、私钥、完整日志和整段会话一律拒绝保存。

## APK Resource Offers

APK 更新仅在 Android 私有目录创建五资源集合的 offer，绝不在启动时复制或覆盖 Termux 文件。维修助手比较实际已安装集合后，才按需把 SHA 不同的单个 APK 归档暂存到 Termux。只有经验证的 `satisfied` 或本机更高的 `superseded` 可以关闭提醒；`dismissed` 只结束本次提醒，不修改资源安装状态。
