# GitHub 本地配置助手

这是稳定小写入口。完整说明见 `GITHUB_CONFIG_HELPER.md`。

运行期路径：

```text
/root/openhouse/docs/GITHUB_CONFIG_HELPER.md
```

它是一个 Ubuntu 本地网页配置器：用户打开页面，点击“授权 GitHub 完整访问”，在 GitHub 官方页面登录，完成后就去 Codex / Claude Code 里直接使用 `git` 和 `gh`。

不需要注册 GitHub OAuth App，不需要 `client_id` 或 `client_secret`。助手复用 GitHub CLI 的官方登录流程：

```bash
SCOPES="repo,workflow,delete_repo,admin:org,admin:public_key,admin:repo_hook,admin:org_hook,admin:gpg_key,notifications,user,project,read:packages,write:packages,delete:packages,codespace,security_events,read:audit_log,gist"

gh auth login --hostname github.com --web --git-protocol https --scopes "$SCOPES"
gh auth setup-git --hostname github.com
gh auth status --hostname github.com
```

这套配置只对同一个 Ubuntu 用户、同一个 `$HOME` 下运行的 Codex / Claude Code 生效。实际权限不会超过用户 GitHub 账号本身已有权限，组织 SSO、ruleset、保护分支和 CODEOWNERS 仍然生效。
