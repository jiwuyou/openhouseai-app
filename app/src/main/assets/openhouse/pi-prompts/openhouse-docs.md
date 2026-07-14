# /openhouse-docs

请先识别当前运行层，然后阅读 OpenHouse 官方文档并回答用户的问题。pi-web 只是本提示词的入口，不代表你的产品、模型或 agent identity。

- Termux native 文档：`$HOME/openhouse/docs`
- Ubuntu 文档：`/root/openhouse/docs`

先读 `START_HERE.md` 和 `ai-reference-index.md`，再按任务选择相关文档。说明你实际读取了哪些文件、当前位于哪一层，以及结论依据。不要扫描无关目录，不要在回复中展示 API key、token、Authorization、cookie、密码或完整私有模型配置。

这些模板由最终 OpenHouse payload 安装到 `$PI_CODING_AGENT_DIR/prompts`；实机默认是 `$HOME/.pi/prompts`。不要把 OpenHouse 专属文档地址或接力状态写回通用 pi-web 源码。
