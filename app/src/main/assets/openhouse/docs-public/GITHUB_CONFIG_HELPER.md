# GitHub 本地配置助手

这是 OpenHouse 内置的 Ubuntu 本地网页配置器。它只负责第一次把 GitHub 登录和 `git` / `gh` 配好；完成后，用户直接去 Codex / Claude Code 里工作，不需要继续使用这个网页。

运行期路径：

```text
/root/openhouse/docs/GITHUB_CONFIG_HELPER.md
```

## 用户怎么用

1. 打开 GitHub 配置助手页面：

```text
http://127.0.0.1:23120/
```

2. 页面检查本机有没有 `git` 和 `gh`。如果缺少，会提示安装。
3. 点击“授权 GitHub 完整访问”。
4. 浏览器打开 GitHub 登录/授权页面；如果不能自动打开浏览器，页面会显示登录链接或设备码。
5. 用户在 GitHub 官方页面完成登录和授权。
6. 本地程序执行 `gh auth setup-git`，让 `git` 使用 GitHub CLI 的凭据。
7. 页面显示已连接后，用户关闭网页，回到 Codex / Claude Code。

之后用户可以直接对 Codex / Claude Code 说：

```text
帮我把当前项目推到 GitHub。
帮我创建一个私有仓库。
帮我创建 PR。
查看 Actions 为什么失败。
帮我管理这个 repo 的 issue。
```

Codex / Claude Code 会在同一个 Ubuntu 环境里调用：

```bash
git push
git clone
gh repo create
gh pr create
gh run view
gh issue list
```

用户不需要复制 token。

## 不需要 OAuth App

这个助手不需要注册 GitHub OAuth App，不需要 `client_id`，也不需要 `client_secret`。它复用 GitHub CLI 的官方登录流程。

核心命令是：

```bash
SCOPES="repo,workflow,delete_repo,admin:org,admin:public_key,admin:repo_hook,admin:org_hook,admin:gpg_key,notifications,user,project,read:packages,write:packages,delete:packages,codespace,security_events,read:audit_log,gist"

gh auth login --hostname github.com --web --git-protocol https --scopes "$SCOPES"
gh auth setup-git --hostname github.com
gh auth status --hostname github.com
```

重新授权：

```bash
SCOPES="repo,workflow,delete_repo,admin:org,admin:public_key,admin:repo_hook,admin:org_hook,admin:gpg_key,notifications,user,project,read:packages,write:packages,delete:packages,codespace,security_events,read:audit_log,gist"

gh auth refresh --hostname github.com --scopes "$SCOPES"
gh auth setup-git --hostname github.com
gh auth status --hostname github.com
```

断开连接：

```bash
gh auth logout --hostname github.com
```

## 权限说明

这组 scopes 让本机 `gh` 能申请较完整的 GitHub 能力，包括仓库、PR、issue、Actions、Packages、Codespaces、gist 和组织相关操作。

但它不会超过用户 GitHub 账号本身已有权限。用户不是组织 owner，就不能管理该组织；组织 SSO、ruleset、保护分支和 CODEOWNERS 仍然生效。

## 同一个 Ubuntu 用户和 HOME

这个配置只对同一个 Ubuntu 用户、同一个 `$HOME` 下运行的工具生效。默认 OpenHouse Ubuntu 环境通常是：

```text
HOME=/root
```

也就是说，助手、`gh`、`git`、Codex 和 Claude Code 应该都在这个 Ubuntu 环境里运行。

如果 Codex / Claude Code 跑在 Docker、远程服务器或云端环境里，本机的 `gh` 登录状态不会自动带过去。那种情况下，需要在目标环境里重新登录，或由用户明确决定是否挂载对应的 `gh` 配置。

## APK 内置方式

助手本体很小，可以作为本地 payload 内置到 APK：

```text
app/src/main/assets/openhouse/product-payloads/github-config-helper.tar
```

安装后放到固定路径：

```text
/root/smallphoneai-repos/github-config-helper
```

service-manager 服务 ID：

```text
github-config-helper
```

默认本机地址：

```text
http://127.0.0.1:23120/
```

`gh` 默认通过 Ubuntu 包管理器安装：

```bash
apt update
apt install -y git gh
```

如果以后要做离线完整包，可以把 `gh` 的 Linux arm64 二进制做成单独 payload；不需要塞进助手本体。

## 页面状态

页面只需要这些状态：

| 状态 | 页面显示 |
| --- | --- |
| 未安装 `gh` | 提示安装 `git` / `gh`。 |
| 未连接 GitHub | 显示“授权 GitHub 完整访问”。 |
| 授权中 | 显示 GitHub 登录链接、设备码或进度。 |
| 已连接 | 显示账号和 `gh auth status` 摘要。 |
| 需要重新授权 | 显示“重新授权”。 |
| 已断开 | 回到未连接状态。 |

## 后端 API

后端只需要固定接口：

```text
GET  /health
GET  /api/status
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/setup-git
POST /api/auth/logout
GET  /api/tasks/:id/events
```

`gh auth login --web` 可能要等用户在浏览器里确认，所以授权接口适合用任务加事件流展示进度。

## 快速检查

查看登录状态：

```bash
gh auth status --hostname github.com
```

重新配置 Git：

```bash
gh auth setup-git --hostname github.com
```

如果 GitHub 网络不稳定，再看：

```text
/root/openhouse/docs/GITHUB_NETWORK_MIRRORS.md
```
