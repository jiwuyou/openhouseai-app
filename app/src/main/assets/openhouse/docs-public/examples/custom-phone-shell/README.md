# OpenHouse Custom Phone Shell

这是 `CUSTOM_FRONTEND_AND_APPS.md` 配套的自定义前端 shell 示例。

它不是新增后台服务，而是纯前端桌面壳：

- 静态文件安装到 `SMALLPHONE_HOME/shells/openhouse-custom-shell`。
- 由 `smallphone-core` 通过 `/shells/openhouse-custom-shell/` 托管。
- 通过 `/api/app-registry` 读取已注册 App。
- 通过 `/api/service-manager/services/:id/*` 控制服务。

安装：

```bash
cd /root/openhouse/docs/examples/custom-phone-shell
bash install-shell.sh
```

如果要让自定义前端代码与现有官方 beta 前端保持一致，用：

```bash
cd /root/openhouse/docs/examples/custom-phone-shell
bash install-from-existing-frontend.sh
```

这会把 `generic-mini-phone-beta` 的前端代码复制到用户 shell 目录，再通过 `/api/user-content` 注册。用户可以在复制后的目录继续修改，不直接改系统前端目录。

访问：

```text
http://127.0.0.1:22000/shells/openhouse-custom-shell/
```
