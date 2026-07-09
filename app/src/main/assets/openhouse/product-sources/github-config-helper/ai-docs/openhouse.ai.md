# GitHub 配置助手

GitHub 配置助手是一个本机 Ubuntu Web App，只负责首次授权 GitHub CLI 并配置 git 凭据助手。授权完成后，用户继续直接使用 Codex 或 Claude Code；本网页不承担仓库管理客户端职责。

## 运行位置

- 服务 ID：`github-config-helper`
- 默认 URL：`http://127.0.0.1:23120/`
- 默认安装目录：`/root/smallphoneai-repos/github-config-helper`
- service-manager provider：`process`
- 默认监听：`127.0.0.1:23120`

## 固定动作

后端只执行白名单动作，不提供通用 shell：

- `POST /api/auth/login`：先执行 `gh auth login --hostname github.com --web --git-protocol https --scopes "$SCOPES"`，成功后自动执行 `gh auth setup-git --hostname github.com`
- `POST /api/auth/refresh`：执行 `gh auth refresh --hostname github.com --scopes "$SCOPES"`
- `POST /api/auth/setup-git`：执行 `gh auth setup-git --hostname github.com`
- `POST /api/auth/logout`：执行 `gh auth logout --hostname github.com`

`SCOPES` 包含 `repo,workflow,delete_repo,admin:org,admin:public_key,admin:repo_hook,admin:org_hook,admin:gpg_key,notifications,user,project,read:packages,write:packages,delete:packages,codespace,security_events,read:audit_log,gist`。

## AI 使用方式

当用户需要配置 GitHub 时，优先打开本地页面让用户点击授权。授权完成后，Codex / Claude Code 可以直接调用：

- `git clone`、`git push`、`git pull`
- `gh repo create`、`gh repo delete`
- `gh pr create`、`gh pr view`
- `gh issue list`
- `gh run view`

不要要求用户复制 token。token 由 GitHub CLI 保存在当前 Ubuntu 用户自己的 gh 配置或凭据存储中。

## 边界

- 实际 GitHub 权限不会超过用户账号已有权限。
- 组织 SSO、保护分支、ruleset、CODEOWNERS、仓库权限仍然生效。
- Codex / Claude Code 必须和 `gh` 运行在同一个 Ubuntu 用户、同一个 `$HOME` 下。
- 如果 AI 运行在 Docker、远程服务器或云端环境，需要额外挂载或重新授权 `~/.config/gh`。
- 日志和回复不得输出 token、cookie、Authorization header、password、secret 或完整凭据文件内容。
