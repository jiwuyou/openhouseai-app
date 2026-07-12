#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
POLICY="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/_ubuntu-mirror-policy.sh"
INSTALL_UBUNTU="$REPO_ROOT/app/src/main/assets/maintainer/install-ubuntu.sh"
UPDATE_UBUNTU="$REPO_ROOT/app/src/main/assets/maintainer/update-ubuntu-packages.sh"
POSTINSTALL_COMMON="$REPO_ROOT/app/src/main/assets/openhouse/scripts-public/_openhouse-postinstall-common.sh"
INSTALL_CONTROLLER="$REPO_ROOT/app/src/main/java/com/termux/app/openhouse/OpenHouseInstallController.java"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_file_contains() {
  local file="$1" value="$2"
  grep -Fq -- "$value" "$file" || fail "$file did not contain: $value"
}

assert_file_not_contains() {
  local file="$1" value="$2"
  if grep -Fq -- "$value" "$file"; then
    fail "$file unexpectedly contained: $value"
  fi
}

[ -f "$POLICY" ] || fail "canonical Ubuntu mirror policy helper is missing"

assert_file_not_contains "$INSTALL_CONTROLLER" \
  'OPENHOUSEAI_UBUNTU_ROOTFS_URL", "https://mirrors.ustc.edu.cn/'
assert_file_contains "$INSTALL_UBUNTU" 'smallphoneai_resolve_ubuntu_rootfs_url'
assert_file_contains "$UPDATE_UBUNTU" 'smallphoneai_resolve_ubuntu_apt_mirror'
assert_file_contains "$UPDATE_UBUNTU" 'smallphoneai_write_canonical_ubuntu_sources'
for stale_policy in \
  'mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images' \
  'mirrors.ustc.edu.cn/ubuntu-cloud-images' \
  'mirrors.nju.edu.cn/ubuntu-cloud-images' \
  'mirrors.tuna.tsinghua.edu.cn/ubuntu-ports' \
  'mirrors.ustc.edu.cn/ubuntu-ports' \
  'mirror.nju.edu.cn/ubuntu-ports'; do
  assert_file_not_contains "$INSTALL_UBUNTU" "$stale_policy"
  assert_file_not_contains "$UPDATE_UBUNTU" "$stale_policy"
done
assert_file_not_contains "$POSTINSTALL_COMMON" \
  'SMALLPHONEAI_UBUNTU_APT_MIRROR:=https://mirrors.ustc.edu.cn/ubuntu-ports'

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT INT HUP TERM
apt_root="$work_dir/etc/apt"
sources_dir="$apt_root/sources.list.d"
backup_dir="$apt_root/openhouseai-backup"
mkdir -p "$sources_dir" "$work_dir/home" "$work_dir/tmp"

printf 'deb http://ports.ubuntu.com/ubuntu-ports noble main\n' > "$apt_root/sources.list"
printf 'Types: deb\nURIs: http://ports.ubuntu.com/ubuntu-ports\n' > "$sources_dir/ubuntu.sources"
printf 'Types: deb\nURIs: https://legacy.example/ubuntu-ports\n' > "$sources_dir/smallphoneai-ubuntu.sources"
printf 'Types: deb\nURIs: https://old.example/ubuntu-ports\n' > "$sources_dir/openhouseai-ubuntu.sources"
printf 'Types: deb\nURIs: https://ppa.launchpadcontent.net/example/stable/ubuntu\nSuites: noble\nComponents: main\n' \
  > "$sources_dir/example-ppa.sources"
ppa_before="$(sha256sum "$sources_dir/example-ppa.sources" | awk '{print $1}')"

extract_update_setup() {
  awk '
    /^log "正在 Ubuntu 内执行 apt update"/ { exit }
    { print }
  ' "$UPDATE_UBUNTU"
}

(
  export HOME="$work_dir/home"
  export TMPDIR="$work_dir/tmp"
  export SMALLPHONEAI_UBUNTU_MIRROR_POLICY="$POLICY"
  export OPENHOUSEAI_UBUNTU_MIRROR_POLICY="$POLICY"
  export OPENHOUSEAI_UBUNTU_APT_MIRROR="https://selected.example/ubuntu-ports"
  export SMALLPHONEAI_UBUNTU_APT_MIRROR="$OPENHOUSEAI_UBUNTU_APT_MIRROR"
  export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID="integration-run"
  export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID="$OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID"
  export OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT="$work_dir/locks"
  export SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT="$OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT"
  export OPENHOUSEAI_UBUNTU_APT_ROOT="$apt_root"
  export SMALLPHONEAI_UBUNTU_APT_ROOT="$apt_root"
  export OPENHOUSEAI_UBUNTU_SOURCES_BACKUP_DIR="$backup_dir"
  export SMALLPHONEAI_UBUNTU_SOURCES_BACKUP_DIR="$backup_dir"

  require_ubuntu() { :; }
  log() { :; }
  run_ubuntu_logged() {
    if [ "${1:-}" = bash ] && [ "${2:-}" = -lc ] \
      && printf '%s' "${3:-}" | grep -Fq 'VERSION_CODENAME'; then
      printf 'noble\n'
      return 0
    fi
    "$@"
  }

  update_setup="$(extract_update_setup)"
  [ -n "$update_setup" ] || fail "maintainer Ubuntu setup block was empty"
  eval "$update_setup"
)

canonical="$sources_dir/openhouseai-ubuntu.sources"
[ -s "$canonical" ] || fail "canonical Ubuntu sources file was not written"
assert_file_contains "$canonical" 'URIs: https://selected.example/ubuntu-ports'
assert_file_contains "$canonical" 'Suites: noble noble-updates noble-backports'
[ ! -e "$apt_root/sources.list" ] || fail "legacy sources.list was not removed"
[ ! -e "$sources_dir/ubuntu.sources" ] || fail "ubuntu.sources was not removed"
[ ! -e "$sources_dir/smallphoneai-ubuntu.sources" ] || fail "legacy smallphoneai source was not removed"
[ -f "$sources_dir/example-ppa.sources" ] || fail "third-party PPA was removed"
ppa_after="$(sha256sum "$sources_dir/example-ppa.sources" | awk '{print $1}')"
[ "$ppa_before" = "$ppa_after" ] || fail "third-party PPA was modified"
for backup in \
  sources.list.bak \
  ubuntu.sources.bak \
  smallphoneai-ubuntu.sources.bak \
  openhouseai-ubuntu.sources.bak; do
  [ -f "$backup_dir/$backup" ] || fail "managed Ubuntu source backup is missing: $backup"
done

capture="$work_dir/postinstall-environment"
mock_bootstrap="$work_dir/mock-bootstrap.sh"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'env | sort > "$TEST_CAPTURE"' \
  > "$mock_bootstrap"
chmod 755 "$mock_bootstrap"

env \
  TEST_CAPTURE="$capture" \
  OPENHOUSE_BOOTSTRAP="$mock_bootstrap" \
  OPENHOUSE_RETRY_MODE=normal \
  OPENHOUSEAI_UBUNTU_APT_MIRROR="https://selected.example/ubuntu-ports" \
  OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=postinstall-run \
  OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT="$work_dir/postinstall-locks" \
  bash -c '. "$1"; oh_run_bootstrap status' _ "$POSTINSTALL_COMMON"

assert_file_contains "$capture" 'OPENHOUSEAI_UBUNTU_APT_MIRROR=https://selected.example/ubuntu-ports'
assert_file_contains "$capture" 'SMALLPHONEAI_UBUNTU_APT_MIRROR=https://selected.example/ubuntu-ports'
assert_file_contains "$capture" 'OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=postinstall-run'
assert_file_contains "$capture" 'SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID=postinstall-run'
assert_file_contains "$capture" "OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT=$work_dir/postinstall-locks"
assert_file_contains "$capture" "SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT=$work_dir/postinstall-locks"

conflict_output="$work_dir/postinstall-conflict"
set +e
env \
  OPENHOUSE_RETRY_MODE=normal \
  OPENHOUSEAI_UBUNTU_APT_MIRROR=https://one.example/ubuntu-ports \
  SMALLPHONEAI_UBUNTU_APT_MIRROR=https://two.example/ubuntu-ports \
  bash -c '. "$1"' _ "$POSTINSTALL_COMMON" > "$conflict_output" 2>&1
conflict_status=$?
set -e
[ "$conflict_status" -ne 0 ] || fail "postinstall accepted conflicting Ubuntu mirror namespaces"
grep -Fq '配置冲突' "$conflict_output" || fail "postinstall conflict error was not explicit"

printf 'Ubuntu mirror App/maintainer/postinstall integration tests passed\n'
