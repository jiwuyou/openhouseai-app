#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
POLICY="$ROOT_DIR/app/src/main/assets/smallphoneai/bootstrap/scripts/_ubuntu-mirror-policy.sh"
RETRY_PROFILE="$ROOT_DIR/app/src/main/assets/smallphoneai/bootstrap/scripts/_retry-profile.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_eq() {
  local expected="$1" actual="$2" label="$3"
  [ "$expected" = "$actual" ] || fail "$label: expected=$expected actual=$actual"
}

assert_file() {
  [ -f "$1" ] || fail "missing file: $1"
}

assert_contains() {
  grep -Fq "$2" "$1" || fail "$1 does not contain: $2"
}

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT INT TERM
export OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT="$TMP_ROOT/locks"
export SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT="$TMP_ROOT/locks"
export SMALLPHONEAI_UBUNTU_MIRROR_LOCK_WAIT_SECONDS=2
CALLS="$TMP_ROOT/curl.calls"
: > "$CALLS"

# shellcheck source=/dev/null
. "$POLICY"

TEST_SCENARIO=default_rootfs
curl() {
  local timeout=0 range=0 max_filesize=0 url="" headers="" arg
  while [ "$#" -gt 0 ]; do
    arg="$1"
    shift
    case "$arg" in
      --max-time)
        timeout="$1"
        shift
        ;;
      --range)
        range=1
        shift
        ;;
      --dump-header)
        headers="$1"
        shift
        ;;
      --max-filesize)
        max_filesize="$1"
        shift
        ;;
      http://*|https://*)
        url="$arg"
        ;;
    esac
  done
  printf '%s|%s|%s|%s|%s\n' \
    "$TEST_SCENARIO" "$timeout" "$range" "$max_filesize" "$url" >> "$CALLS"

  case "$TEST_SCENARIO" in
    default_rootfs)
      printf 'HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-1048575/104857600\r\n\r\n' > "$headers"
      printf '206 1048576 1'
      return 0
      ;;
    bad_rootfs_range)
      printf 'HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 1-1048576/104857600\r\n\r\n' > "$headers"
      printf '206 1048576 1'
      return 0
      ;;
    rootfs_ignores_range)
      printf 'HTTP/1.1 200 OK\r\nContent-Length: 104857600\r\n\r\n' > "$headers"
      printf '200 2097152'
      return 63
      ;;
    rootfs_list_retry)
      case "$url:$timeout" in
        *mirrors.tuna*:16) printf '000 0'; return 28 ;;
        *mirror.nju*:16) printf '404 0'; return 22 ;;
        *mirrors.tuna*:32)
          printf 'HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-1048575/104857600\r\n\r\n' > "$headers"
          printf '206 1048576 1'
          return 0
          ;;
      esac
      ;;
    cn_speed_rootfs)
      case "$url" in
        *mirrors.tuna*) speed=4 ;;
        *mirrors.nju*) speed=1 ;;
        *mirrors.ustc*) speed=2 ;;
        *cloud-images.ubuntu*) speed=3 ;;
        *) printf '404 0'; return 22 ;;
      esac
      printf 'HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-1048575/104857600\r\n\r\n' > "$headers"
      printf '206 1048576 %s' "$speed"
      return 0
      ;;
    apt_tuna_transient_nju_success)
      case "$url" in
        *mirrors.tuna*) printf '000'; return 28 ;;
        *mirror.nju*) printf '200'; return 0 ;;
        *) printf '404'; return 22 ;;
      esac
      ;;
    cn_speed_apt)
      case "$url" in
        *mirrors.tuna*) printf '200 100 4' ;;
        *mirror.nju*) printf '200 100 1' ;;
        *mirrors.ustc*) printf '200 100 2' ;;
        *ports.ubuntu.com*) printf '200 100 3' ;;
        *) printf '404 0 1'; return 22 ;;
      esac
      return 0
      ;;
    retry_transient_only)
      case "$url:$timeout" in
        *mirrors.tuna*:16) printf '000'; return 28 ;;
        *mirrors.tuna*:32) printf '200'; return 0 ;;
        *mirror.nju*:*) printf '404'; return 22 ;;
        *ports.ubuntu.com*:*) printf '503'; return 22 ;;
        *mirrors.ustc*:*) printf '403'; return 22 ;;
      esac
      ;;
    cache_first)
      printf '200'
      return 0
      ;;
    cache_must_not_probe)
      printf '000'
      return 28
      ;;
    *)
      printf '000'
      return 1
      ;;
  esac
}

assert_eq transient "$(smallphoneai_classify_mirror_failure 28 000)" 'curl timeout classification'
assert_eq transient "$(smallphoneai_classify_mirror_failure 22 503)" 'HTTP 503 classification'
assert_eq permanent "$(smallphoneai_classify_mirror_failure 22 404)" 'HTTP 404 classification'
assert_eq success "$(smallphoneai_classify_mirror_failure 0 206)" 'HTTP 206 classification'
TEST_SCENARIO=bad_rootfs_range
set +e
smallphoneai_probe_ubuntu_rootfs_mirror 'https://example.invalid/rootfs.tar.xz' 16
status=$?
set -e
assert_eq 1 "$status" 'strict Content-Range rejection'
assert_eq permanent "${SMALLPHONEAI_UBUNTU_MIRROR_LAST_CLASS:-}" 'invalid Content-Range classification'
SMALLPHONEAI_UBUNTU_MIRROR_POLICY_LOADED=1 \
  bash -c '. "$1"; declare -F smallphoneai_write_canonical_ubuntu_sources >/dev/null' \
  bash "$POLICY" || fail 'policy must load functions when sentinel is inherited without functions'

expected_rootfs_order="$(cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://mirrors.nju.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://mirrors.ustc.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
EOF
)"
assert_eq "$expected_rootfs_order" "$(smallphoneai_ubuntu_rootfs_candidates arm64)" 'rootfs candidate order'

expected_apt_order="$(cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
https://mirror.nju.edu.cn/ubuntu-ports
http://ports.ubuntu.com/ubuntu-ports
https://mirrors.ustc.edu.cn/ubuntu-ports
EOF
)"
assert_eq "$expected_apt_order" "$(smallphoneai_ubuntu_apt_candidates)" 'apt candidate order'

PROFILE="$TMP_ROOT/profile.json"
printf '%s\n' '{"schema":3,"country":"SG","region":"asia","networkClass":"global","validated":true}' > "$PROFILE"
export OPENHOUSEAI_TERMUX_MIRROR_PROFILE="$PROFILE"
export SMALLPHONEAI_TERMUX_MIRROR_PROFILE="$PROFILE"
expected_global_rootfs_order="$(cat <<'EOF'
https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://mirrors.nju.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://mirrors.ustc.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
EOF
)"
assert_eq "$expected_global_rootfs_order" "$(smallphoneai_ubuntu_rootfs_candidates arm64)" 'global rootfs candidate order'
assert_eq "$(cat <<'EOF'
https://ports.ubuntu.com/ubuntu-ports
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
https://mirror.nju.edu.cn/ubuntu-ports
https://mirrors.ustc.edu.cn/ubuntu-ports
EOF
)" "$(smallphoneai_ubuntu_apt_candidates)" 'global apt candidate order'
printf '%s\n' '{"schema":3,"country":"CN","region":"chinese_mainland","networkClass":"cn","validated":true}' > "$PROFILE"
assert_eq "$(cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://mirrors.nju.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://mirrors.ustc.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-arm64-root.tar.xz
EOF
)" "$(smallphoneai_ubuntu_rootfs_candidates arm64)" 'cn rootfs candidate order'
assert_eq "$(cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
https://mirror.nju.edu.cn/ubuntu-ports
https://mirrors.ustc.edu.cn/ubuntu-ports
https://ports.ubuntu.com/ubuntu-ports
EOF
)" "$(smallphoneai_ubuntu_apt_candidates)" 'cn apt candidate order'
unset OPENHOUSEAI_TERMUX_MIRROR_PROFILE SMALLPHONEAI_TERMUX_MIRROR_PROFILE

printf '%s\n' '{"schema":3,"country":"CN","region":"chinese_mainland","networkClass":"cn","validated":true}' > "$PROFILE"
export OPENHOUSEAI_TERMUX_MIRROR_PROFILE="$PROFILE"
export SMALLPHONEAI_TERMUX_MIRROR_PROFILE="$PROFILE"
export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=cn-speed-rootfs
export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID=cn-speed-rootfs
: > "$CALLS"
TEST_SCENARIO=cn_speed_rootfs
resolved="$(smallphoneai_resolve_ubuntu_rootfs_url arm64)"
assert_eq 'https://mirrors.nju.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz' "$resolved" 'CN rootfs fastest candidate'
assert_eq 4 "$(wc -l < "$CALLS" | tr -d ' ')" 'CN rootfs tests all candidates'
export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=cn-speed-apt
export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID=cn-speed-apt
: > "$CALLS"
TEST_SCENARIO=cn_speed_apt
resolved="$(smallphoneai_resolve_ubuntu_apt_mirror noble)"
assert_eq 'https://mirror.nju.edu.cn/ubuntu-ports' "$resolved" 'CN apt fastest candidate'
assert_eq 4 "$(wc -l < "$CALLS" | tr -d ' ')" 'CN apt tests all candidates'
unset OPENHOUSEAI_TERMUX_MIRROR_PROFILE SMALLPHONEAI_TERMUX_MIRROR_PROFILE

export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=rootfs-default
export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID=rootfs-default
: > "$CALLS"
TEST_SCENARIO=default_rootfs
resolved="$(smallphoneai_resolve_ubuntu_rootfs_url arm64)"
assert_eq "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz" "$resolved" 'rootfs resolver'
assert_eq 1 "$(wc -l < "$CALLS" | tr -d ' ')" 'rootfs probe count'
assert_contains "$CALLS" '|16|1|2097152|https://mirrors.tuna.tsinghua.edu.cn/'

ignored_range_list='https://example.invalid/ignores-range-rootfs.tar.xz'
export OPENHOUSEAI_UBUNTU_ROOTFS_URLS="$ignored_range_list"
export SMALLPHONEAI_UBUNTU_ROOTFS_URLS="$ignored_range_list"
export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=rootfs-ignore-range
export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID=rootfs-ignore-range
: > "$CALLS"
TEST_SCENARIO=rootfs_ignores_range
set +e
smallphoneai_resolve_ubuntu_rootfs_url arm64 >/dev/null 2>"$TMP_ROOT/ignore-range.err"
status=$?
set -e
assert_eq 69 "$status" 'HTTP 200 ignored Range resolver status'
assert_eq 1 "$(wc -l < "$CALLS" | tr -d ' ')" 'HTTP 200 permanent failure is not retried'
assert_eq permanent "${SMALLPHONEAI_UBUNTU_MIRROR_LAST_CLASS:-}" 'curl exit 63 with HTTP 200 is permanent'
assert_contains "$CALLS" 'rootfs_ignores_range|16|1|2097152|https://example.invalid/ignores-range-rootfs.tar.xz'
unset OPENHOUSEAI_UBUNTU_ROOTFS_URLS SMALLPHONEAI_UBUNTU_ROOTFS_URLS

rootfs_list="$(cat <<'EOF'
https://mirror.nju.edu.cn/custom/rootfs-arm64.tar.xz
https://mirrors.tuna.tsinghua.edu.cn/custom/rootfs-arm64.tar.xz
EOF
)"
export OPENHOUSEAI_UBUNTU_ROOTFS_URLS="$rootfs_list"
export SMALLPHONEAI_UBUNTU_ROOTFS_URLS="$rootfs_list"
export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=rootfs-list-order
export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID=rootfs-list-order
: > "$CALLS"
TEST_SCENARIO=default_rootfs
resolved="$(smallphoneai_resolve_ubuntu_rootfs_url arm64)"
assert_eq 'https://mirror.nju.edu.cn/custom/rootfs-arm64.tar.xz' "$resolved" 'rootfs list preserves user order'
assert_eq 1 "$(wc -l < "$CALLS" | tr -d ' ')" 'rootfs list first candidate probe count'
assert_contains "$CALLS" '|16|1|2097152|https://mirror.nju.edu.cn/custom/rootfs-arm64.tar.xz'

export OPENHOUSEAI_UBUNTU_ROOTFS_URLS="https://mirrors.tuna.tsinghua.edu.cn/custom/rootfs-arm64.tar.xz"
export SMALLPHONEAI_UBUNTU_ROOTFS_URLS="https://mirror.nju.edu.cn/custom/rootfs-arm64.tar.xz"
: > "$CALLS"
set +e
smallphoneai_resolve_ubuntu_rootfs_url arm64 >/dev/null 2>"$TMP_ROOT/list-conflict.err"
status=$?
set -e
assert_eq 64 "$status" 'rootfs list namespace conflict status'
assert_contains "$TMP_ROOT/list-conflict.err" 'candidate list 配置冲突'
assert_eq 0 "$(wc -l < "$CALLS" | tr -d ' ')" 'list conflict fails before probe'

export OPENHOUSEAI_UBUNTU_ROOTFS_URL=https://example.invalid/single-rootfs.tar.xz
export SMALLPHONEAI_UBUNTU_ROOTFS_URL=https://example.invalid/single-rootfs.tar.xz
resolved="$(smallphoneai_resolve_ubuntu_rootfs_url arm64)"
assert_eq 'https://example.invalid/single-rootfs.tar.xz' "$resolved" 'single rootfs override precedes conflicting lists'
unset OPENHOUSEAI_UBUNTU_ROOTFS_URL SMALLPHONEAI_UBUNTU_ROOTFS_URL

rootfs_list="$(cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/custom/rootfs-arm64.tar.xz
https://mirror.nju.edu.cn/custom/rootfs-arm64.tar.xz
EOF
)"
export OPENHOUSEAI_UBUNTU_ROOTFS_URLS="$rootfs_list"
export SMALLPHONEAI_UBUNTU_ROOTFS_URLS="$rootfs_list"
export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=rootfs-list-retry
export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID=rootfs-list-retry
: > "$CALLS"
TEST_SCENARIO=rootfs_list_retry
resolved="$(smallphoneai_resolve_ubuntu_rootfs_url arm64)"
assert_eq 'https://mirrors.tuna.tsinghua.edu.cn/custom/rootfs-arm64.tar.xz' "$resolved" 'rootfs list transient retry result'
assert_eq 3 "$(wc -l < "$CALLS" | tr -d ' ')" 'rootfs list 16/32 probe count'
assert_contains "$CALLS" 'rootfs_list_retry|32|1|2097152|https://mirrors.tuna.tsinghua.edu.cn/custom/rootfs-arm64.tar.xz'
unset OPENHOUSEAI_UBUNTU_ROOTFS_URLS SMALLPHONEAI_UBUNTU_ROOTFS_URLS

export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=apt-fallback
export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID=apt-fallback
: > "$CALLS"
TEST_SCENARIO=apt_tuna_transient_nju_success
resolved="$(smallphoneai_resolve_ubuntu_apt_mirror noble)"
assert_eq 'https://mirror.nju.edu.cn/ubuntu-ports' "$resolved" 'first-pass fallback to NJU'
assert_eq 2 "$(wc -l < "$CALLS" | tr -d ' ')" 'first-pass fallback probe count'
assert_eq "$(cat <<'EOF'
apt_tuna_transient_nju_success|16|0|0|https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/dists/noble/InRelease
apt_tuna_transient_nju_success|16|0|0|https://mirror.nju.edu.cn/ubuntu-ports/dists/noble/InRelease
EOF
)" "$(cat "$CALLS")" 'first-pass fallback order'

export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=apt-retry
export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID=apt-retry
: > "$CALLS"
TEST_SCENARIO=retry_transient_only
resolved="$(smallphoneai_resolve_ubuntu_apt_mirror noble)"
assert_eq 'https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports' "$resolved" 'transient retry result'
assert_eq 5 "$(wc -l < "$CALLS" | tr -d ' ')" 'transient retry probe count'
assert_eq 1 "$(grep -c '|16|0|0|https://mirror.nju' "$CALLS" || true)" 'NJU first pass only'
assert_eq 0 "$(grep -c '|32|0|0|https://mirror.nju' "$CALLS" || true)" 'NJU permanent failure not retried'
assert_eq 1 "$(grep -c '|32|0|0|https://mirrors.tuna' "$CALLS" || true)" 'TUNA transient retry'

unset OPENHOUSEAI_UBUNTU_ROOTFS_URL SMALLPHONEAI_UBUNTU_ROOTFS_URL
export OPENHOUSEAI_UBUNTU_ROOTFS_URL=https://example.invalid/openhouse-rootfs.tar.xz
export SMALLPHONEAI_UBUNTU_ROOTFS_URL=https://example.invalid/smallphone-rootfs.tar.xz
set +e
smallphoneai_resolve_ubuntu_rootfs_url arm64 >/dev/null 2>"$TMP_ROOT/conflict.err"
status=$?
set -e
assert_eq 64 "$status" 'rootfs namespace conflict status'
assert_contains "$TMP_ROOT/conflict.err" '配置冲突'

export SMALLPHONEAI_UBUNTU_ROOTFS_URL="$OPENHOUSEAI_UBUNTU_ROOTFS_URL"
: > "$CALLS"
resolved="$(smallphoneai_resolve_ubuntu_rootfs_url arm64)"
assert_eq "$OPENHOUSEAI_UBUNTU_ROOTFS_URL" "$resolved" 'matching override wins'
assert_eq 0 "$(wc -l < "$CALLS" | tr -d ' ')" 'override bypasses probes'
unset OPENHOUSEAI_UBUNTU_ROOTFS_URL SMALLPHONEAI_UBUNTU_ROOTFS_URL

export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=apt-cache
export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID=apt-cache
: > "$CALLS"
TEST_SCENARIO=cache_first
first="$(smallphoneai_resolve_ubuntu_apt_mirror noble)"
TEST_SCENARIO=cache_must_not_probe
second="$(smallphoneai_resolve_ubuntu_apt_mirror noble)"
assert_eq "$first" "$second" 'run-id cached selection'
assert_eq 1 "$(wc -l < "$CALLS" | tr -d ' ')" 'run-id cache avoids second probe'

APT_ROOT="$TMP_ROOT/apt-root"
mkdir -p "$APT_ROOT/sources.list.d"
printf 'deb http://legacy.invalid stable main\n' > "$APT_ROOT/sources.list"
printf 'official base\n' > "$APT_ROOT/sources.list.d/ubuntu.sources"
printf 'legacy smallphone managed\n' > "$APT_ROOT/sources.list.d/smallphoneai-ubuntu.sources"
printf 'previous canonical\n' > "$APT_ROOT/sources.list.d/openhouseai-ubuntu.sources"
printf 'Types: deb\nURIs: https://ppa.example.invalid/repo\n' > "$APT_ROOT/sources.list.d/example-ppa.sources"
export OPENHOUSEAI_UBUNTU_APT_ROOT="$APT_ROOT"
export SMALLPHONEAI_UBUNTU_APT_ROOT="$APT_ROOT"
export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID=writer
export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID=writer
target="$(smallphoneai_write_canonical_ubuntu_sources 'https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports' noble)"
assert_eq "$APT_ROOT/sources.list.d/openhouseai-ubuntu.sources" "$target" 'canonical target path'
assert_file "$APT_ROOT/openhouseai-backup/sources.list.bak"
assert_file "$APT_ROOT/openhouseai-backup/ubuntu.sources.bak"
assert_file "$APT_ROOT/openhouseai-backup/smallphoneai-ubuntu.sources.bak"
assert_file "$APT_ROOT/openhouseai-backup/openhouseai-ubuntu.sources.bak"
assert_file "$APT_ROOT/sources.list.d/example-ppa.sources"
assert_contains "$target" 'URIs: https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports'
assert_contains "$target" 'Suites: noble noble-updates noble-backports'
assert_contains "$target" 'Suites: noble-security'
assert_eq 'deb http://legacy.invalid stable main' "$(cat "$APT_ROOT/openhouseai-backup/sources.list.bak")" 'initial sources.list backup'
assert_eq 'official base' "$(cat "$APT_ROOT/openhouseai-backup/ubuntu.sources.bak")" 'initial ubuntu.sources backup'
assert_eq 'legacy smallphone managed' "$(cat "$APT_ROOT/openhouseai-backup/smallphoneai-ubuntu.sources.bak")" 'initial smallphone backup'
assert_eq 'previous canonical' "$(cat "$APT_ROOT/openhouseai-backup/openhouseai-ubuntu.sources.bak")" 'initial canonical backup'

# Reappearing managed base files must be removed without overwriting first backups.
printf 'reappeared sources.list\n' > "$APT_ROOT/sources.list"
printf 'reappeared ubuntu.sources\n' > "$APT_ROOT/sources.list.d/ubuntu.sources"
printf 'reappeared smallphone sources\n' > "$APT_ROOT/sources.list.d/smallphoneai-ubuntu.sources"
target="$(smallphoneai_write_canonical_ubuntu_sources 'https://mirror.nju.edu.cn/ubuntu-ports' noble)"
[ ! -e "$APT_ROOT/sources.list" ] || fail 'reappeared sources.list remains active'
[ ! -e "$APT_ROOT/sources.list.d/ubuntu.sources" ] || fail 'reappeared ubuntu.sources remains active'
[ ! -e "$APT_ROOT/sources.list.d/smallphoneai-ubuntu.sources" ] || fail 'reappeared smallphone sources remains active'
assert_eq 'deb http://legacy.invalid stable main' "$(cat "$APT_ROOT/openhouseai-backup/sources.list.bak")" 'sources.list first backup preserved'
assert_eq 'official base' "$(cat "$APT_ROOT/openhouseai-backup/ubuntu.sources.bak")" 'ubuntu.sources first backup preserved'
assert_eq 'legacy smallphone managed' "$(cat "$APT_ROOT/openhouseai-backup/smallphoneai-ubuntu.sources.bak")" 'smallphone first backup preserved'
assert_eq 'previous canonical' "$(cat "$APT_ROOT/openhouseai-backup/openhouseai-ubuntu.sources.bak")" 'canonical first backup preserved'
assert_contains "$target" 'URIs: https://mirror.nju.edu.cn/ubuntu-ports'
assert_file "$APT_ROOT/sources.list.d/example-ppa.sources"

(
  unset OPENHOUSEAI_UBUNTU_APT_MIRROR SMALLPHONEAI_UBUNTU_APT_MIRROR
  OPENHOUSE_RETRY_MODE=cn SMALLPHONEAI_RETRY_MODE=cn \
    bash -c '. "$1"; test -z "${OPENHOUSEAI_UBUNTU_APT_MIRROR:-}"; test -z "${SMALLPHONEAI_UBUNTU_APT_MIRROR:-}"' \
    bash "$RETRY_PROFILE"
) || fail 'CN retry profile must not pin Ubuntu apt to USTC'

printf 'PASS: canonical Ubuntu mirror policy\n'
