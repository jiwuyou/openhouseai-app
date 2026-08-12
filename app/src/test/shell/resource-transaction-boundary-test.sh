#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
manager="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-manager"
importer="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-import"
setup="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup"
start="$repo_dir/app/src/main/assets/maintainer/start-control-plane-termux-native.sh"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

bash -n "$manager"
bash -n "$importer"
bash -n "$setup"
bash -n "$start"

for forbidden in \
  'start_control_plane' \
  'verify_live_stack' \
  'register_resources' \
  '/api/v1/services' \
  '/api/v1/health' \
  '20765' \
  'service-daemon start' \
  'sv up'; do
  ! grep -Fq "$forbidden" "$manager" || fail "content manager contains runtime operation: $forbidden"
done

grep -Fq 'CONFIG_PATH="$HOME/.config/openhouseai/service-manager/config.json"' "$manager" \
  || fail 'service-manager content installer does not use the canonical config path'
grep -Fq 'BIND="127.0.0.1:20087"' "$manager" \
  || fail 'service-manager content installer does not use the canonical bind'
grep -Fq 'INSTALL_SERVICE=0' "$manager" \
  || fail 'service-manager content installer may install runit during content convergence'
grep -Fq '"$directory/scripts/install.sh"' "$manager" \
  || fail 'runtime static installer is not selected explicitly'

grep -Fq 'delivery:$delivery,content:$content,activation:$activation,status:"pending"' "$importer" \
  || fail 'import receipt does not preserve independent pending activation'
grep -Fq '"$inbox/.imported"' "$importer" \
  || fail 'importer does not publish the content-installed marker'
! grep -Fq 'status":"satisfied"' "$importer" \
  || fail 'importer still marks an APK offer satisfied'
! grep -Fq 'trap '\''rm -rf -- "$transaction"' "$importer" \
  || fail 'importer EXIT trap still references a local transaction variable'

for required in \
  'activate_runtime()' \
  'activation_fail()' \
  'canonical_auth_failed' \
  'registry_sync_failed' \
  'wuxianpi_health_failed' \
  'service-manager token rotate --config "$CANONICAL_SM_CONFIG"' \
  'POST /api/v1/registry/sync'; do
  grep -Fq "$required" "$setup" || fail "setup activation is missing: $required"
done

grep -Fq 'diagnostic canonicalAuth' "$start" \
  || grep -Fq 'diagnostic_result canonicalAuth' "$start" \
  || fail 'control-plane start does not report canonical auth separately'
grep -Fq 'diagnostic_result instanceArguments' "$start" \
  || fail 'control-plane start does not report instance argument matching separately'

printf 'Resource transaction boundary contract passed\n'
