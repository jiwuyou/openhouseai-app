# SmallPhone Front Beta 部署

本文给 OpenHouse 第二阶段 Agent 使用。APK 会携带完整的 `smallphone.tar`，但 APK 更新只负责把资源投递到 Termux；实际替换、注册、启动和验收由第二阶段 Agent 完成。当前默认第二阶段 Agent 是 AionUI，用户明确选择其它 Agent 时可以替换，执行规则不变。

运行分层固定为：

```text
Termux native service-manager
  -> proot-distro provider
  -> Ubuntu /root/smallphoneai-repos/smallphone-active
  -> smallphone-core / api（运行端口来自 endpoint 快照）
  -> smallphone-frontend-beta / web（运行端口来自 endpoint 快照）
```

用户数据目录 `/root/smallphoneai-repos/smallphone-home` 必须保留。不要删除、覆盖或用 payload 中的示例数据替换它。

下面第 1 至第 4 节应在同一个 Termux native shell 中连续执行，使 `resource_dir`、`reason` 和 `pending_marker` 保持为本次选中的版本。若 Agent 为每一节新开 shell，必须先重新执行第 1 节解析这些变量，不能凭记忆复用旧路径。

## 1. 选择 APK 资源目录

在 Termux native 执行。优先读取待处理标记中的绝对 `resourceDir`；只有标记缺失、损坏或指向不完整目录时，才按目录名排序回退到最新的完整 `apk-*` 目录：

```bash
set -eu
resources_root="$HOME/.local/share/openhouseai/update-resources"
pending_marker="$resources_root/PENDING_APK_RESOURCES.json"
resource_dir=""
reason=""

command -v jq >/dev/null 2>&1 || pkg install -y jq

if [ -s "$pending_marker" ]; then
  reason="$(jq -r '.reason // empty' "$pending_marker" 2>/dev/null || true)"
  marker_dir="$(jq -r '.resourceDir // empty' "$pending_marker" 2>/dev/null || true)"
  case "$marker_dir" in
    /*) [ -f "$marker_dir/.complete" ] && resource_dir="$marker_dir" ;;
  esac
fi

if [ -z "$resource_dir" ]; then
  for candidate in $(find "$resources_root" -mindepth 1 -maxdepth 1 -type d -name 'apk-*' | sort -r); do
    [ -f "$candidate/.complete" ] || continue
    resource_dir="$candidate"
    break
  done
fi

[ -n "$resource_dir" ] || { echo "没有找到完整 APK 资源目录" >&2; exit 1; }
jq -e '.apkVersionCode and (.verifiedFiles | type == "array")' "$resource_dir/.complete" >/dev/null
jq -e . "$resource_dir/product-payloads/manifest.json" >/dev/null
jq -e . "$resource_dir/product-payloads/payload-manifest.json" >/dev/null
[ -f "$resource_dir/product-payloads/smallphone.tar" ] || { echo "缺少 smallphone.tar" >&2; exit 1; }
printf 'resource_dir=%s\nreason=%s\n' "$resource_dir" "${reason:-none}"
```

`reason=first_install` 表示首次安装仍在消费资源。第二阶段 Agent 可以帮助检查或修复，但不能删除该标记；它应由 Android 在首次安装真正成功后清理。`reason=apk_update` 才是由第二阶段 Agent 完成迁移并在全部验收通过后清理的更新任务。

## 2. 使用完整 payload 部署

不要只复制 `index.html`、`main.js` 等几个文件。SmallPhone Front Beta 的模块之间有版本依赖，增量复制容易形成新旧文件混用并导致白屏。必须让 APK 中的完整 `smallphone.tar` 经过官方 bootstrap 组件流程安装。

在 Termux native 执行：

```bash
cd "$resource_dir/bootstrap"

SMALLPHONEAI_COMPONENT_TARGETS=smallphone \
SMALLPHONEAI_FORCE_PAYLOAD_REFRESH=1 \
SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="$resource_dir/product-payloads" \
SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="$resource_dir/product-payloads" \
bash bootstrap.sh components

bash bootstrap.sh registry-sync
bash bootstrap.sh start
bash bootstrap.sh status
```

两个 payload 目录变量必须同时指向当前 `resource_dir` 的 sibling `product-payloads`，避免 bootstrap 回退到旧的 `.smallphoneai-bootstrap/apk-assets`。该流程应只刷新 `/root/smallphoneai-repos/smallphone-active` 的系统代码，保留 `/root/smallphoneai-repos/smallphone-home`。不要手工在 Ubuntu 内长期启动 Node，也不要在 Ubuntu 内另起 service-manager。

## 3. 常驻和验收

按 `SERVICE_MANAGER.md` 的认证模板准备 `SM_URL` 与 `/tmp/openhouse-sm-curl.cfg`，确认两个服务由 Termux service-manager 管理，并设置为常驻：

```bash
for service_id in smallphone-core smallphone-frontend-beta; do
  curl -q -fsS --max-time 10 -X PUT -K /tmp/openhouse-sm-curl.cfg \
    -H 'Content-Type: application/json' -d '{"resident":true}' \
    "$SM_URL/api/v1/services/$service_id/residency"
  curl -q -fsS --max-time 10 -K /tmp/openhouse-sm-curl.cfg \
    "$SM_URL/api/v1/services/$service_id/status"
done
```

最终必须同时通过：

- `smallphone-core` 和 `smallphone-frontend-beta` 状态为运行中，provider 为 `proot-distro`，常驻为 `true`。
- `$HOME/.config/openhouseai/runtime/endpoints.json` 为 `schemaVersion=1`、`state=ready`，且没有过期。
- `smallphone-core + api` 与 `smallphone-frontend-beta + web` 均存在，不允许回退到 preferred 端口。
- 使用快照中的 Core URL 请求 `/health` 和 `/api/app-registry` 成功，且返回的小 App 列表非空。
- 普通浏览器和 Android WebView 打开快照中的 Front URL 都能看到应用图标，不是白屏。
- 当前 Beta 的消息、联系人可见入口数量为 `0`；后端历史数据可以保留，但桌面、Dock、Tab 和设置中不应出现入口。
- `/root/smallphoneai-repos/smallphone-home` 仍存在，用户数据未被覆盖。

如果页面白屏，优先确认完整 payload 是否部署，以及 `generic-mini-phone-beta/apps/workflows/api.js` 等依赖是否来自同一份 `smallphone.tar`；不要再次进行少量文件增量覆盖。

动态地址验收命令：

```bash
ENDPOINTS_FILE="$HOME/.config/openhouseai/runtime/endpoints.json"
jq -e '.schemaVersion == 1 and .state == "ready"' "$ENDPOINTS_FILE" >/dev/null
CORE_URL="$(jq -er '.endpoints[] | select(.serviceId == "smallphone-core" and .name == "api") | .url' "$ENDPOINTS_FILE")"
FRONT_URL="$(jq -er '.endpoints[] | select(.serviceId == "smallphone-frontend-beta" and .name == "web") | .url' "$ENDPOINTS_FILE")"
curl -fsS "${CORE_URL%/}/health"
curl -fsS "${CORE_URL%/}/api/app-registry" | jq -e . >/dev/null
curl -fsS "$FRONT_URL" >/dev/null
printf 'core=%s\nfront=%s\n' "$CORE_URL" "$FRONT_URL"
```

## 4. 完成 APK 更新标记

只有 `reason=apk_update`、标记仍指向本次 `resource_dir`，并且上一节全部验收通过时，第二阶段 Agent 才能删除待处理标记：

```bash
[ -f "$pending_marker" ] || exit 0
[ "$(jq -r '.reason // empty' "$pending_marker")" = "apk_update" ] || {
  echo "不是 apk_update，保留待处理标记" >&2
  exit 1
}
[ "$(jq -r '.resourceDir // empty' "$pending_marker")" = "$resource_dir" ] || {
  echo "待处理标记已变化，保留标记" >&2
  exit 1
}
[ -f "$resource_dir/.complete" ] || exit 1
rm -f -- "$pending_marker"
```

任何一项验收失败都应保留标记并写入交接结果；`reason=first_install` 时绝不能由第二阶段 Agent 删除。

## 给第二阶段 Agent 的话

```text
请作为 OpenHouse 第二阶段 Agent（默认 AionUI，除非用户明确改用其它 Agent），阅读 /root/openhouse/docs/SMALLPHONE_FRONT_BETA_DEPLOY.md，优先从 Termux 的 PENDING_APK_RESOURCES.json 读取绝对 resourceDir，使用其中完整 smallphone.tar 和官方 bootstrap 组件流程部署 SmallPhone Front Beta，保留 /root/smallphoneai-repos/smallphone-home；从 ~/.config/openhouseai/runtime/endpoints.json 读取 smallphone-core/api 与 smallphone-frontend-beta/web 的实际 URL，完成 app-registry、proot-distro provider、常驻状态、WebView 非白屏以及消息/联系人入口为 0 的验收，不得回退固定端口；仅当 reason=apk_update 且全部通过时删除待处理标记。
```
