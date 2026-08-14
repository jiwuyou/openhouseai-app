#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
generator="$repo_dir/scripts/generate-market-script-resources.sh"
root="$repo_dir/distribution/market-script-resources"
set_file="$root/resource-sets/openhouse-core-stack/2026.08.14.1/manifest.json"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

"$generator" --check
jq -e '
  .schema == 2 and .id == "openhouse-core-stack" and
  .version == "2026.08.14.1" and .sequence == 2026081401 and
  (.guide.title | length) > 0 and (.guide.markdown | contains("市场不可用")) and
  ([.resources[].id] | sort) == [
    "openhouse-control-plane-start",
    "openhouse-inspect-control-plane",
    "openhouse-install-runtime-components",
    "openhouse-register-component",
    "openhouse-repair-control-plane",
    "openhouse-resource-import",
    "openhouse-resource-manager",
    "openhouse-runtime",
    "openhouse-start-service-manager",
    "openhouse-start-smallphone",
    "openhouse-termux-services-env",
    "openhouse-web",
    "service-manager",
    "wuxianpi-setup",
    "wuyou"
  ]
' "$set_file" >/dev/null

while IFS=$'\t' read -r id version archive member; do
  path="$root/resources/$id/$version/$archive"
  [[ -s "$path" ]] || fail "missing archive $id@$version"
  listing="$(tar -tzf "$path" | sed 's#^\./##' | sed '/^$/d')"
  [[ "$listing" == "$member" ]] || fail "$id must contain only $member, got: $listing"
  mode="$(tar -tvzf "$path" | awk 'NR == 1 {print $1}')"
  [[ "$mode" == -rwx* ]] || fail "$id member is not executable: $mode"
  first="$(tar -xOzf "$path" "$member" | sed -n '1p')"
  [[ "$first" == '#!/data/data/com.termux/files/usr/bin/bash' ]] \
    || fail "$id has a non-Termux Bash shebang: $first"
  bash -n <(tar -xOzf "$path" "$member")
done <<'EOF'
openhouse-resource-manager	1.0.0	openhouse-resource-manager.tgz	openhouse-resource-manager
openhouse-resource-import	1.0.0	openhouse-resource-import.tgz	openhouse-resource-import
wuxianpi-setup	1.0.0	wuxianpi-setup.tgz	wuxianpi-setup
openhouse-install-runtime-components	1.0.0	openhouse-install-runtime-components.tgz	50-install-runtime-components.sh
openhouse-start-smallphone	1.0.0	openhouse-start-smallphone.tgz	60-start-smallphone.sh
openhouse-register-component	1.0.0	openhouse-register-component.tgz	register-openhouse-component.sh
openhouse-control-plane-start	1.0.0	openhouse-control-plane-start.tgz	openhouse-control-plane-start
openhouse-termux-services-env	1.0.0	openhouse-termux-services-env.tgz	_termux-services-env.sh
openhouse-start-service-manager	1.0.0	openhouse-start-service-manager.tgz	start-service-manager.sh
openhouse-repair-control-plane	1.0.0	openhouse-repair-control-plane.tgz	repair-control-plane.sh
openhouse-inspect-control-plane	1.0.0	openhouse-inspect-control-plane.tgz	inspect-control-plane.sh
EOF

runtime="$root/resources/openhouse-runtime/0.2.0+registry.1/runtime-aarch64.tgz"
if [[ -f "$repo_dir/../../wuxianpi/packaging/termux/bundle/register-service.sh" ]]; then
  grep -Fq 'http://127.0.0.1:{{port:runtime}}/health' \
    "$repo_dir/../../wuxianpi/packaging/termux/bundle/register-service.sh" \
    || fail 'WuxianPi registration source is missing templated health'
fi
register="$(tar -xOzf "$runtime" ./scripts/register-service.sh 2>/dev/null \
  || tar -xOzf "$runtime" scripts/register-service.sh)"
grep -Fq '"ports": [{"name": "runtime"' <<<"$register" \
  || fail 'runtime resource is missing the dynamic runtime port'
grep -Fq 'http://127.0.0.1:{{port:runtime}}/health' <<<"$register" \
  || fail 'runtime resource is missing templated health'
grep -Fq '"home": "$HOME"' <<<"$register" \
  || fail 'runtime resource is missing Termux runtime home'

for resource in \
  openhouse-install-runtime-components/1.0.0/openhouse-install-runtime-components.tgz:50-install-runtime-components.sh \
  openhouse-start-smallphone/1.0.0/openhouse-start-smallphone.tgz:60-start-smallphone.sh \
  openhouse-repair-control-plane/1.0.0/openhouse-repair-control-plane.tgz:repair-control-plane.sh; do
  archive="${resource%%:*}"
  member="${resource#*:}"
  content="$(tar -xOzf "$root/resources/$archive" "$member")"
  grep -Fq 'libexec/openhouse/_termux-services-env.sh' <<<"$content" \
    || fail "$member does not prefer the market-installed Termux service environment"
done

manager="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-manager"
setup="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup"
grep -Fq 'apply_content "$SET_FILE" "$row_plan" "$transaction" 0' "$manager" \
  || fail 'market resources are not committed one at a time'
grep -Fq '"ai": {' "$setup" || fail 'WuxianPi component is missing the ai layer'
grep -Fq '{component:$component[0],services:[{id:"yuanshengwuxianpi",service:$service[0]}]}' "$setup" \
  || fail 'registry/apply payload does not wrap the service'
grep -Fq 'POST /api/v1/registry/apply' "$setup" \
  || fail 'activation does not call registry/apply'

printf 'Market script resource contract passed\n'
