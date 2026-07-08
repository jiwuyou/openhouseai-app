# OpenHouse Paths And Ports

本文件只记录最基础的路径和端口约定。

## 基础路径

- Termux 主目录：`/data/data/com.termux/files/home`
- 官方文档目录：`/data/data/com.termux/files/home/product-docs/official`
- Agent 笔记目录：`/data/data/com.termux/files/home/product-docs/agent-notes`

## Ubuntu 侧理解方式

在 Ubuntu 中，文档可能通过以下任一方式暴露：

- 直接访问 Termux 路径
- 映射到 `~/product-docs/official`
- 映射到 `~/product-docs/agent-notes`

如果短路径存在，优先用短路径。

## 端口

默认情况下，OpenCode 运行在本机端口：

- `4096`

默认访问地址：

- `http://127.0.0.1:4096/`

如果产品后续允许修改默认端口，应以维护器界面中的当前设置为准。

## 注意

- `9222` 可能用于浏览器调试端口
- 不要假设所有端口始终开启
- 先检查，再访问
