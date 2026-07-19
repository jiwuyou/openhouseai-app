# Pi 对话迁移与同步

Pi 原始 JSONL 是唯一权威数据。Android 只保存可重建的 UI 缓存，不维护另一份 Operit 对话数据库。

默认目录：

```text
Termux/Linux/macOS: ~/.pi/agent/sessions/
Windows:            %USERPROFILE%\.pi\agent\sessions\
```

导出包格式：

```text
openhouse-pi-conversations-v1.zip
├── manifest.json
└── sessions/
    └── ...原始 Pi JSONL
```

备份不得包含 `auth.json`、API Key、网关/Bridge Token、锁文件或缓存索引。导入后由 Pi 重建索引。历史消息、thinking、工具调用和工具结果保持不变；跨系统后旧的绝对工作目录可能不存在，这只影响重新执行工具，不影响查看历史。

同步以已稳定落盘的会话文件为单位，只在 `agent_end` 或显式保存后上传。会话 ID、内容哈希和修改版本共同判断变化。两端同时修改同一会话时不合并 JSONL 行，而是保留冲突副本作为 fork，避免丢失任一端历史。同步后端失败不得阻塞本地对话。
