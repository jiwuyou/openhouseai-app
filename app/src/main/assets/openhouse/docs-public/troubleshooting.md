# 排障入口

这是给 pi-agent 和其他 AI 工作台使用的稳定小写入口。完整说明见同目录：

- `RECOVERY.md`
- `SERVICE_MANAGER.md`
- `ENVIRONMENT.md`
- `GITHUB_NETWORK_MIRRORS.md`

排障原则：

1. 先诊断，不直接重装。
2. 先恢复 service-manager，再恢复上层服务。
3. Ubuntu 坏了，用 Termux 修。
4. Termux 坏了，用 Android App 的维护/底座修复能力。
5. 默认保留用户项目、模型配置、API key、本地知识库和 agent 笔记。
6. 清数据、删除 home、重装 Ubuntu、重建 prefix 都需要用户明确确认。

快速检查：

```bash
cd "$HOME/.smallphoneai-bootstrap"
bash bootstrap.sh status
bash bootstrap.sh check
bash bootstrap.sh hooks
```

如果 pi-web、pi-agent 或 CloudCLI 不可访问，优先看 service-manager 状态。如果后置工具缺失，执行 `/root/openhouse/scripts/check-ai-tools.sh`，再按需安装，不要把后置工具缺失当作首装失败。

