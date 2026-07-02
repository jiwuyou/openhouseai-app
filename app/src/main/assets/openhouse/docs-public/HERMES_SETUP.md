# Hermes 安装和配置

Hermes 是可选高级能力。它可以出现在 pi-agent 新建会话的默认新手提示词里，但必须明确告诉用户：安装和配置会花比较久，不影响 OpenHouse、pi-agent、Claude Code 的基础使用。

Hermes 不进入 APK 默认核心 payload，不预置默认后台服务，也不应污染 OpenHouse 主 Node/Python 环境。

安装后的稳定路径：

```text
/root/openhouseai-docs/official/HERMES_SETUP.md
~/openhouseai-docs/official/HERMES_SETUP.md
```

pi-web 默认提示词应引用这个路径，并明确标注 Hermes 是可选高级任务。

后置入口脚本：

```bash
/root/openhouse/scripts/install-hermes.sh
```

该脚本只准备独立目录和 uv 环境。Hermes 上游启动方式可能变化，必须按当前 README 前台测通后再注册 service-manager。

## 上游项目

当前参考项目：

```text
https://github.com/nesquena/hermes-webui
```

上游说明可能变化。pi-agent 执行前应优先查看该仓库当前 README、安装脚本和依赖要求。

## 安装原则

- 在 Ubuntu 侧安装，不在 Termux 外层安装。
- 使用 `uv` 创建独立 Python 环境。
- 工作目录放在 `/root/.local/share/openhouseai/hermes-webui` 或用户指定目录。
- 日志放在 `/root/.smallphoneai/logs/hermes-webui.log`。
- 不修改 OpenHouse 主 Python、Node、pi-web runtime 或 APK payload。
- 需要后台运行时，注册到 service-manager。

## 准备 uv 独立环境

在 Ubuntu 内执行：

```bash
cd /root
command -v uv >/dev/null 2>&1 || curl -LsSf https://astral.sh/uv/install.sh | sh
export PATH="$HOME/.local/bin:$PATH"
mkdir -p /root/.local/share/openhouseai
cd /root/.local/share/openhouseai
```

克隆或更新 Hermes：

```bash
if [ ! -d hermes-webui/.git ]; then
  git clone https://github.com/nesquena/hermes-webui.git hermes-webui
fi
cd hermes-webui
git pull --ff-only || true
```

创建独立环境：

```bash
uv venv .venv
. .venv/bin/activate
```

然后按上游 README 的当前方式安装。不要假设 APK 内已有 Hermes 包。

## 启动方式

先以前台方式启动一次，确认端口、命令和日志：

```bash
cd /root/.local/share/openhouseai/hermes-webui
. .venv/bin/activate
python --version
```

后续启动命令以 Hermes 当前 README 为准。确认能访问本地页面后，再注册 service-manager。

## service-manager 注册

示例服务定义：

```json
{
  "name": "hermes-webui",
  "description": "Hermes WebUI optional service",
  "provider": "process",
  "command": [
    "bash",
    "-lc",
    "cd /root/.local/share/openhouseai/hermes-webui && . .venv/bin/activate && exec ./start.sh"
  ],
  "working_dir": "/root/.local/share/openhouseai/hermes-webui",
  "env": {
    "PATH": "/root/.local/bin:/usr/local/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin"
  },
  "restart": {
    "mode": "on_failure",
    "max_retries": 3
  },
  "health": [
    {
      "type": "http",
      "url": "http://127.0.0.1:8787/",
      "interval": "30s",
      "timeout": "5s"
    }
  ],
  "enabled": true,
  "tags": ["openhouseai", "optional", "hermes"]
}
```

如果上游启动命令不是 `./start.sh`，按实际命令替换。不要注册一个尚未前台测通的命令。

写入时把上面的 JSON 保存为：

```bash
mkdir -p "$HOME/.config/openhouseai/service-manager/services.d"
$EDITOR "$HOME/.config/openhouseai/service-manager/services.d/hermes-webui.json"
```

写入 JSON 后，重启或修复控制中枢：

```bash
cd "$HOME/.smallphoneai-bootstrap"
bash bootstrap.sh start
```

验证：

```bash
service-manager list
service-manager status hermes-webui
```

## 注册到菜单

如果需要让 Hermes 出现在 OpenHouseAI 菜单，新增独立组件注册，示例：

```json
{
  "id": "hermes-webui",
  "enabled": true,
  "shellMenu": {
    "title": "Hermes",
    "subtitle": "可选高级 WebUI",
    "section": "ai",
    "order": 180,
    "visible": true,
    "entry": {
      "type": "webview",
      "url": "http://127.0.0.1:8787/"
    },
    "controlEntry": {
      "type": "service-control",
      "title": "控制",
      "serviceRefs": [
        "service-manager://services/hermes-webui"
      ]
    }
  },
  "smallphoneApp": {},
  "serviceManager": {
    "services": [
      {
        "name": "hermes-webui",
        "serviceRef": "service-manager://services/hermes-webui"
      }
    ]
  },
  "ai": {}
}
```

组件注册只描述入口和 service-manager 引用，不写 shell 命令。具体启动命令必须放在 service-manager 服务定义中。

## 停止和卸载

停止服务：

```bash
service-manager stop hermes-webui 2>/dev/null || true
```

如果 CLI 不支持 stop 子命令，按 `SERVICE_MANAGER.md` 使用 API 停止。

卸载时先停止服务，再删除可选注册和安装目录：

```bash
rm -f "$HOME/.config/openhouseai/service-manager/services.d/hermes-webui.json"
rm -f "$HOME/.config/openhouseai/components.d/hermes-webui.json"
rm -rf /root/.local/share/openhouseai/hermes-webui
```

删除前必须确认用户不再需要 Hermes 数据。不要删除 OpenHouse、pi-agent、CloudCLI 或用户项目目录。

## 给 pi-agent 的提示词

默认新手提示词可以这样写：

```text
请按 /root/openhouseai-docs/official/HERMES_SETUP.md 安装和配置 Hermes。注意这是可选高级能力，安装和配置会花比较久。请使用 https://github.com/nesquena/hermes-webui，并把它放到 uv 独立环境里，不要污染 OpenHouse 主环境。安装前先确认上游 README，安装后注册到 service-manager，提供启动、停止、卸载方法。
```

## 相关文档

- `/root/openhouseai-docs/official/OPTIONAL_EXTERNAL_TOOLS.md`
- `/root/openhouseai-docs/official/SERVICE_MANAGER.md`
- `/root/openhouseai-docs/official/RECOVERY.md`
- `/root/openhouseai-docs/official/TERMINAL_PROFILES.md`
