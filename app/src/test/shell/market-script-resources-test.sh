#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
generator="$repo_dir/scripts/generate-market-script-resources.sh"
root="$repo_dir/distribution/market-script-resources"
set_file="$root/resource-sets/openhouse-core-stack/2026.08.17.2/manifest.json"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

"$generator" --check
distribution_index="${OPENHOUSEAI_DISTRIBUTION_PACKAGES_ROOT:-$repo_dir/../../wuxianpi/packaging/termux/preinstalled-packages}/index.json"
[[ -s "$distribution_index" ]] || fail "prepared WuxianPi Package index is missing"
jq -e --slurpfile index "$distribution_index" '
  .schema == 2 and .id == "openhouse-core-stack" and
  .version == "2026.08.17.2" and .sequence == 2026081702 and
  (.guide.title | length) > 0 and (.guide.markdown | contains("市场不可用")) and
  ([.resources[] | select(.kind == "wuxianpi-package") | .id] | sort) ==
    ([$index[0].packages[].packageId | "wuxianpi-package-" + .] | sort) and
  (all(.resources[]; (.id | startswith("wuxianpi-package-")) or .kind == null))
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
openhouse-resource-manager	1.0.5	openhouse-resource-manager.tgz	openhouse-resource-manager
openhouse-resource-import	1.0.4	openhouse-resource-import.tgz	openhouse-resource-import
wuxianpi-setup	1.0.4	wuxianpi-setup.tgz	wuxianpi-setup
openhouse-bootstrap	1.0.1	openhouse-bootstrap.tgz	bootstrap.sh
openhouse-install-ubuntu	1.0.1	openhouse-install-ubuntu.tgz	20-install-ubuntu.sh
openhouse-ubuntu-mirror-policy	1.0.1	openhouse-ubuntu-mirror-policy.tgz	_ubuntu-mirror-policy.sh
openhouse-update-ubuntu-packages	1.0.1	openhouse-update-ubuntu-packages.tgz	30-update-ubuntu-packages.sh
openhouse-retry-profile	1.0.1	openhouse-retry-profile.tgz	_retry-profile.sh
openhouse-install-runtime-components	1.0.1	openhouse-install-runtime-components.tgz	50-install-runtime-components.sh
openhouse-start-smallphone	1.0.1	openhouse-start-smallphone.tgz	60-start-smallphone.sh
openhouse-register-component	1.0.1	openhouse-register-component.tgz	register-openhouse-component.sh
openhouse-control-plane-start	1.0.1	openhouse-control-plane-start.tgz	openhouse-control-plane-start
openhouse-termux-services-env	1.0.1	openhouse-termux-services-env.tgz	_termux-services-env.sh
openhouse-start-service-manager	1.0.1	openhouse-start-service-manager.tgz	start-service-manager.sh
openhouse-repair-control-plane	1.0.1	openhouse-repair-control-plane.tgz	repair-control-plane.sh
openhouse-inspect-control-plane	1.0.1	openhouse-inspect-control-plane.tgz	inspect-control-plane.sh
EOF

runtime_version="$(jq -r '.resources[] | select(.id == "openhouse-runtime") | .version' "$set_file")"
runtime="$root/resources/openhouse-runtime/$runtime_version/runtime-aarch64.tgz"
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
openhouse-install-runtime-components/1.0.1/openhouse-install-runtime-components.tgz:50-install-runtime-components.sh \
  openhouse-start-smallphone/1.0.1/openhouse-start-smallphone.tgz:60-start-smallphone.sh \
  openhouse-repair-control-plane/1.0.1/openhouse-repair-control-plane.tgz:repair-control-plane.sh; do
  archive="${resource%%:*}"
  member="${resource#*:}"
  content="$(tar -xOzf "$root/resources/$archive" "$member")"
  grep -Fq 'libexec/openhouse/_termux-services-env.sh' <<<"$content" \
    || fail "$member does not prefer the market-installed Termux service environment"
done

while IFS=$'\t' read -r package_id package_version package_archive; do
  package_resource="$root/resources/$package_id/$package_version/$package_archive"
  [[ -s "$package_resource" ]] || fail "WuxianPi Package resource is missing: $package_id@$package_version"
  package_listing="$(tar -tzf "$package_resource")"
  grep -Fxq './package-resource.json' <<<"$package_listing" \
    || fail "$package_id does not contain package-resource.json"
  grep -Fxq './install-plan.json' <<<"$package_listing" \
    || fail "$package_id does not contain install-plan.json"
  grep -Fxq './source/wuxianpi-package.json' <<<"$package_listing" \
    || fail "$package_id does not contain a Package manifest"
done < <(jq -r '.resources[] | select(.kind == "wuxianpi-package") | [.id,.version,.archive] | @tsv' "$set_file")

manager="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-manager"
setup="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup"
grep -Fq 'apply_content "$SET_FILE" "$row_plan" "$transaction" 0' "$manager" \
  || fail 'market resources are not committed one at a time'
grep -Fq '(.resources | type == "array")' "$manager" \
  || fail 'resource manager does not accept partial allowlisted sets'
grep -Fq 'record_installed_resource "$SET_FILE" "$id"' "$manager" \
  || fail 'resource manager does not merge each completed market resource'
if grep -Fq '([.resources[].id] | sort) ==' "$manager"; then
  fail 'resource manager still requires an exact resource count or member set'
fi
grep -Fq '"ai": {' "$setup" || fail 'WuxianPi component is missing the ai layer'
grep -Fq '{component:$component[0],services:[{id:"yuanshengwuxianpi",service:$service[0]}]}' "$setup" \
  || fail 'registry/apply payload does not wrap the service'
grep -Fq 'POST /api/v1/registry/apply' "$setup" \
  || fail 'activation does not call registry/apply'
grep -Fq 'market_bootstrap_file()' "$setup" \
  || fail 'setup does not resolve the market Ubuntu bootstrap'
grep -Fq 'libexec/openhouse/bootstrap' "$setup" \
  || fail 'setup does not use the fixed market Bootstrap location'
for mapping in \
  'openhouse-bootstrap) source="$directory/bootstrap.sh"; target="$PREFIX/libexec/openhouse/bootstrap/bootstrap.sh"' \
  'openhouse-install-ubuntu) source="$directory/20-install-ubuntu.sh"; target="$PREFIX/libexec/openhouse/bootstrap/scripts/20-install-ubuntu.sh"' \
  'openhouse-update-ubuntu-packages) source="$directory/30-update-ubuntu-packages.sh"; target="$PREFIX/libexec/openhouse/bootstrap/scripts/30-update-ubuntu-packages.sh"' \
  'openhouse-ubuntu-mirror-policy) source="$directory/_ubuntu-mirror-policy.sh"; target="$PREFIX/libexec/openhouse/bootstrap/scripts/_ubuntu-mirror-policy.sh"' \
  'openhouse-retry-profile) source="$directory/_retry-profile.sh"; target="$PREFIX/libexec/openhouse/bootstrap/scripts/_retry-profile.sh"'; do
  grep -Fq "$mapping" "$manager" || fail "missing fixed Ubuntu bootstrap mapping: $mapping"
done
grep -Fq 'DISTRIBUTION_PACKAGES=' "$manager" \
  || fail 'WuxianPi Package distribution directory is missing'
grep -Fq 'wuxianpi-package-*)' "$manager" \
  || fail 'WuxianPi Package resources are not handled dynamically'

# A market-only installation has no legacy APK resource directory. The setup
# command must still dispatch both Ubuntu stages through the fixed layout.
test_root="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-market-bootstrap.XXXXXX")"
trap 'rm -rf "$test_root"' EXIT
prefix="$test_root/prefix"
home="$test_root/home"
mkdir -p "$prefix/bin" "$prefix/libexec/openhouse/bootstrap/scripts" "$home"
ln -s "$(command -v bash)" "$prefix/bin/bash"
install -m 700 "$setup" "$prefix/bin/wuxianpi-setup"
for script in \
  "$prefix/libexec/openhouse/bootstrap/scripts/20-install-ubuntu.sh" \
  "$prefix/libexec/openhouse/bootstrap/scripts/30-update-ubuntu-packages.sh" \
  "$prefix/libexec/openhouse/bootstrap/scripts/_ubuntu-mirror-policy.sh" \
  "$prefix/libexec/openhouse/bootstrap/scripts/_retry-profile.sh"; do
  printf '%s\n' '#!/data/data/com.termux/files/usr/bin/bash' 'exit 0' >"$script"
  chmod 700 "$script"
done
cat >"$prefix/libexec/openhouse/bootstrap/bootstrap.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
printf '%s\n' "$*" >> "$HOME/bootstrap.calls"
EOF
chmod 700 "$prefix/libexec/openhouse/bootstrap/bootstrap.sh"
HOME="$home" PREFIX="$prefix" bash "$prefix/bin/wuxianpi-setup" ubuntu
[[ "$(cat "$home/bootstrap.calls")" == $'ubuntu\nubuntu-packages' ]] \
  || fail 'market Bootstrap did not run both Ubuntu stages'

printf 'Market script resource contract passed\n'
