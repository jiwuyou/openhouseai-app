#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/.." && pwd)"
SOURCE_REPO="/root/projects/service-manager"
PAYLOAD_DIR="$REPO_ROOT/app/src/main/assets/openhouse/product-payloads"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/root/android-sdk/ndk/29.0.14206865}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:-24}"
RUST_TARGET="aarch64-linux-android"
RUST_TOOLCHAIN="${RUST_TOOLCHAIN:-nightly-2026-07-05}"
MODE=""
VERIFY_PAYLOAD_DIR="$PAYLOAD_DIR"

usage() {
  cat <<'EOF'
Usage:
  scripts/build-service-manager-payload.sh --build-local
  scripts/build-service-manager-payload.sh --verify-only [--payload-dir DIR]

Production identity contract:
  - Source is fixed to /root/projects/service-manager.
  - Cargo package must be service-manager 0.3.3.
  - The script itself clean-builds target/service-manager-payload-build/aarch64-linux-android/release/service-manager.
  - Arbitrary --binary, --source, and --output substitutions are rejected.
  - The payload binary must exactly match that Cargo output.

Local cross-build toolchain:
  Rust toolchain: nightly-2026-07-05
  Rust target: aarch64-linux-android
  Android NDK: /root/android-sdk/ndk/29.0.14206865
  Android API: 24
  Linker: $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${ANDROID_API_LEVEL}-clang
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --build-local)
      [ -z "$MODE" ] || { printf 'error: choose exactly one mode\n' >&2; exit 2; }
      MODE=build
      shift
      ;;
    --verify-only)
      [ -z "$MODE" ] || { printf 'error: choose exactly one mode\n' >&2; exit 2; }
      MODE=verify
      shift
      ;;
    --payload-dir)
      VERIFY_PAYLOAD_DIR="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'error: unsupported argument: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[ -n "$MODE" ] || { printf 'error: --build-local or --verify-only is required\n' >&2; exit 2; }
if [ "$MODE" = build ] && [ "$VERIFY_PAYLOAD_DIR" != "$PAYLOAD_DIR" ]; then
  printf 'error: --payload-dir is only valid with --verify-only\n' >&2
  exit 2
fi
[ -f "$SOURCE_REPO/Cargo.toml" ] && [ -d "$SOURCE_REPO/src" ] || {
  printf 'error: authoritative service-manager source is unavailable: %s\n' "$SOURCE_REPO" >&2
  exit 2
}

command -v cargo >/dev/null 2>&1 || { printf 'error: cargo is unavailable\n' >&2; exit 2; }
command -v rustup >/dev/null 2>&1 || { printf 'error: rustup is unavailable\n' >&2; exit 2; }
command -v file >/dev/null 2>&1 || { printf 'error: file command is unavailable\n' >&2; exit 2; }
command -v readelf >/dev/null 2>&1 || { printf 'error: readelf command is unavailable\n' >&2; exit 2; }

cargo_metadata="$(rustup run "$RUST_TOOLCHAIN" cargo metadata \
  --manifest-path "$SOURCE_REPO/Cargo.toml" \
  --format-version 1 \
  --no-deps)"
python3 - "$SOURCE_REPO" "$cargo_metadata" <<'PY'
import json
import os
import sys

source_repo = os.path.realpath(sys.argv[1])
metadata = json.loads(sys.argv[2])
expected_manifest = os.path.join(source_repo, "Cargo.toml")
package = next(
    (
        item
        for item in metadata.get("packages", [])
        if os.path.realpath(item.get("manifest_path", "")) == expected_manifest
    ),
    None,
)
if package is None:
    raise SystemExit("error: authoritative Cargo root package is missing")
if package.get("name") != "service-manager" or package.get("version") != "0.3.3":
    raise SystemExit(
        f"error: expected Cargo package service-manager 0.3.3, got {package.get('name')} {package.get('version')}"
    )
manifest_dir = os.path.realpath(os.path.dirname(package.get("manifest_path", "")))
if manifest_dir != source_repo:
    raise SystemExit(f"error: Cargo root {manifest_dir} is not authoritative source {source_repo}")
PY

source_commit="$(git -C "$SOURCE_REPO" rev-parse HEAD)"
source_status="$(git -C "$SOURCE_REPO" status --porcelain=v1 --untracked-files=all)"
if [ -n "$source_status" ]; then
  source_dirty=true
else
  source_dirty=false
fi
source_status_sha256="$(printf '%s' "$source_status" | sha256sum | awk '{print $1}')"

source_tree_sha256="$(python3 - "$SOURCE_REPO" <<'PY'
import hashlib
import pathlib
import sys

root = pathlib.Path(sys.argv[1]).resolve()
excluded_roots = {".git", ".agents", ".codex", "target", "dist"}
excluded_files = {"service-manager"}
paths = []
for path in root.rglob("*"):
    relative = path.relative_to(root)
    if not path.is_file() or relative.parts[0] in excluded_roots or relative.as_posix() in excluded_files:
        continue
    paths.append(path)
digest = hashlib.sha256()
for path in sorted(paths, key=lambda item: item.relative_to(root).as_posix()):
    relative = path.relative_to(root).as_posix().encode()
    digest.update(len(relative).to_bytes(4, "big"))
    digest.update(relative)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
print(digest.hexdigest())
PY
)"
source_tree_id="sha256:$source_tree_sha256"

LINKER_PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${ANDROID_API_LEVEL}-clang"
LLVM_AR="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
NDK_VERSION="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$ANDROID_NDK_HOME/source.properties" | head -n 1)"
RUSTC_VERSION="$(rustup run "$RUST_TOOLCHAIN" rustc --version)"
CARGO_VERSION="$(rustup run "$RUST_TOOLCHAIN" cargo --version)"
BUILD_TARGET_DIR="$SOURCE_REPO/target/service-manager-payload-build"
BINARY="$BUILD_TARGET_DIR/$RUST_TARGET/release/service-manager"
CLEAN_COMMAND="rustup run $RUST_TOOLCHAIN cargo clean --manifest-path $SOURCE_REPO/Cargo.toml --target-dir $BUILD_TARGET_DIR"
BUILD_COMMAND="$CLEAN_COMMAND && CARGO_TARGET_DIR=$BUILD_TARGET_DIR CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$LINKER_PATH CC_aarch64_linux_android=$LINKER_PATH AR_aarch64_linux_android=$LLVM_AR rustup run $RUST_TOOLCHAIN cargo build --manifest-path $SOURCE_REPO/Cargo.toml --target $RUST_TARGET --release --locked"

verify_elf() {
  local binary="$1"
  local file_output elf_header program_headers dynamic_section version_info

  [ -f "$binary" ] && [ -x "$binary" ] || {
    printf 'error: Cargo output is missing or not executable: %s\n' "$binary" >&2
    return 2
  }
  file_output="$(file "$binary")"
  elf_header="$(readelf -h "$binary")"
  program_headers="$(readelf -l "$binary")"
  dynamic_section="$(readelf -d "$binary")"
  version_info="$(readelf --version-info "$binary")"
  printf 'binary verification:\n%s\n' "$file_output" >&2
  printf '%s\n' "$program_headers" | grep -F 'Requesting program interpreter:' >&2 || true
  printf '%s\n' "$file_output" | grep -Eq 'ELF 64-bit LSB.*(ARM aarch64|aarch64)' || return 2
  printf '%s\n' "$elf_header" | grep -Eq 'Class:[[:space:]]+ELF64' || return 2
  printf '%s\n' "$elf_header" | grep -Eq 'Machine:[[:space:]]+AArch64' || return 2
  printf '%s\n' "$program_headers" | grep -Fq 'Requesting program interpreter: /system/bin/linker64' || return 2
  if printf '%s\n%s\n' "$dynamic_section" "$version_info" | grep -Eq 'GLIBC_|ld-linux|libc\.so\.6'; then
    printf 'error: glibc dependency or symbol version found in %s\n' "$binary" >&2
    return 2
  fi
  if printf '%s\n' "$file_output" | grep -Eqi 'x86-64|x86_64'; then
    printf 'error: x86_64 binary rejected: %s\n' "$binary" >&2
    return 2
  fi
}

verify_payload() {
  local payload_dir="$1"
  local binary_sha256
  [ -f "$BINARY" ] || { printf 'error: script-produced Cargo output is missing: %s\n' "$BINARY" >&2; return 2; }
  verify_elf "$BINARY"
  binary_sha256="$(sha256sum "$BINARY" | awk '{print $1}')"
  python3 - \
    "$payload_dir" "$SOURCE_REPO" "$source_commit" "$source_dirty" \
    "$source_status_sha256" "$source_tree_sha256" "$source_tree_id" \
    "$binary_sha256" <<'PY'
import hashlib
import io
import json
import os
import re
import sys
import tarfile

(
    payload_dir,
    source_repo,
    expected_commit,
    expected_dirty,
    expected_status_sha,
    expected_source_sha,
    expected_source_id,
    expected_binary_sha,
) = sys.argv[1:]
expected_dirty = expected_dirty == "true"
archive_path = os.path.join(payload_dir, "service-manager.tar")
if not os.path.isfile(archive_path):
    raise SystemExit(f"error: payload archive is missing: {archive_path}")

def digest_bytes(data):
    return hashlib.sha256(data).hexdigest()

with tarfile.open(archive_path, "r:*") as tar:
    members = {
        (member.name[2:] if member.name.startswith("./") else member.name): member
        for member in tar.getmembers()
    }
    binary_member = members.get("service-manager")
    metadata_member = members.get("metadata/build.json")
    cargo_member = members.get("Cargo.toml")
    if not binary_member or not metadata_member or not cargo_member:
        raise SystemExit("error: payload lacks service-manager, metadata/build.json, or Cargo.toml")
    binary = tar.extractfile(binary_member).read()
    metadata = json.loads(tar.extractfile(metadata_member).read().decode("utf-8"))
    cargo_toml = tar.extractfile(cargo_member).read().decode("utf-8")
    payload_tree = hashlib.sha256()
    files = []
    for name, member in members.items():
        if not member.isfile() or name in {"service-manager", "metadata/build.json"}:
            continue
        files.append((name, member))
    for name, member in sorted(files):
        encoded = name.encode()
        payload_tree.update(len(encoded).to_bytes(4, "big"))
        payload_tree.update(encoded)
        payload_tree.update(tar.extractfile(member).read())

binary_sha = digest_bytes(binary)
if binary_sha != expected_binary_sha:
    raise SystemExit("error: payload binary does not match script-produced Cargo output")
if len(binary) < 20 or binary[:4] != b"\x7fELF" or binary[4] != 2 or binary[5] != 1:
    raise SystemExit("error: payload binary is not little-endian ELF64")
if int.from_bytes(binary[18:20], "little") != 183 or b"/system/bin/linker64\x00" not in binary:
    raise SystemExit("error: payload binary is not Android/bionic AArch64")
if any(value in binary for value in (b"GLIBC_", b"/lib/ld-linux", b"libc.so.6")):
    raise SystemExit("error: payload binary contains glibc identity")
if not re.search(r'^name = "service-manager"$', cargo_toml, re.MULTILINE):
    raise SystemExit("error: packaged Cargo.toml package name is stale")
if not re.search(r'^version = "0.3.3"$', cargo_toml, re.MULTILINE):
    raise SystemExit("error: packaged Cargo.toml version is stale")

expected = {
    "component": "service-manager",
    "version": "0.3.3",
    "sourceCommit": expected_commit,
    "sourceDirty": expected_dirty,
    "sourceStatusSha256": expected_status_sha,
    "sourceTreeSha256": expected_source_sha,
    "sourceTreeId": expected_source_id,
    "payloadSourceTreeSha256": payload_tree.hexdigest(),
    "binarySha256": expected_binary_sha,
}
for key, value in expected.items():
    if metadata.get(key) != value:
        raise SystemExit(f"error: payload build metadata {key} is stale: {metadata.get(key)!r} != {value!r}")
if metadata.get("sourceRepo") != "https://github.com/jiwuyou/service-manager":
    raise SystemExit("error: payload build metadata sourceRepo is stale")
if not isinstance(metadata.get("buildToolchain"), dict) or metadata["buildToolchain"].get("method") != "local-android-ndk-cross":
    raise SystemExit("error: payload build metadata toolchain identity is stale")

entries = []
for manifest_name, array_name in (("manifest.json", "components"), ("payload-manifest.json", "payloads")):
    path = os.path.join(payload_dir, manifest_name)
    document = json.load(open(path, encoding="utf-8"))
    entry = next((item for item in document[array_name] if item.get("id") == "service-manager"), None)
    if entry is None:
        raise SystemExit(f"error: {manifest_name} has no service-manager entry")
    entries.append(entry)
    for key, value in expected.items():
        manifest_key = "id" if key == "component" else key
        if entry.get(manifest_key) != value:
            raise SystemExit(f"error: {manifest_name} {manifest_key} is stale: {entry.get(manifest_key)!r} != {value!r}")
    for key in ("sourceRepo", "buildToolchain"):
        if entry.get(key) != metadata.get(key):
            raise SystemExit(f"error: {manifest_name} {key} differs from payload build metadata")
    if entry.get("binarySha256") != binary_sha:
        raise SystemExit(f"error: {manifest_name} binarySha256 does not match payload binary")
    archive_sha = hashlib.sha256(open(archive_path, "rb").read()).hexdigest()
    if entry.get("sha256") != archive_sha or entry.get("size") != os.path.getsize(archive_path):
        raise SystemExit(f"error: {manifest_name} archive hash/size is stale")
print("service-manager payload identity and provenance verified")
PY
}

if [ "$MODE" = verify ]; then
  verify_payload "$VERIFY_PAYLOAD_DIR"
  exit 0
fi

rustup target list --installed --toolchain "$RUST_TOOLCHAIN" | grep -Fxq "$RUST_TARGET" || {
  printf 'error: Rust target %s is not installed for %s\n' "$RUST_TARGET" "$RUST_TOOLCHAIN" >&2
  exit 2
}
[ -x "$LINKER_PATH" ] && [ -x "$LLVM_AR" ] || {
  printf 'error: Android NDK toolchain is unavailable under %s\n' "$ANDROID_NDK_HOME" >&2
  exit 2
}
printf 'local cross-build command:\n  %s\n' "$BUILD_COMMAND" >&2
rustup run "$RUST_TOOLCHAIN" cargo clean \
  --manifest-path "$SOURCE_REPO/Cargo.toml" \
  --target-dir "$BUILD_TARGET_DIR"
CARGO_TARGET_DIR="$BUILD_TARGET_DIR" \
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LINKER_PATH" \
CC_aarch64_linux_android="$LINKER_PATH" \
AR_aarch64_linux_android="$LLVM_AR" \
  rustup run "$RUST_TOOLCHAIN" cargo build \
    --manifest-path "$SOURCE_REPO/Cargo.toml" \
    --target "$RUST_TARGET" \
    --release \
    --locked
verify_elf "$BINARY"
binary_sha256="$(sha256sum "$BINARY" | awk '{print $1}')"

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT INT HUP TERM
stage="$work_dir/service-manager"
mkdir -p "$stage"
while IFS= read -r -d '' source; do
  relative="${source#"$SOURCE_REPO"/}"
  destination="$stage/$relative"
  mkdir -p "$(dirname "$destination")"
  cp -p "$source" "$destination"
done < <(
  find "$SOURCE_REPO" -type f \
    ! -path "$SOURCE_REPO/.git/*" \
    ! -path "$SOURCE_REPO/target/*" \
    ! -path "$SOURCE_REPO/dist/*" \
    ! -path "$SOURCE_REPO/.agents/*" \
    ! -path "$SOURCE_REPO/.codex/*" \
    ! -path "$SOURCE_REPO/service-manager" \
    -print0 | sort -z
)
install -m 0755 "$BINARY" "$stage/service-manager"
while IFS= read -r -d '' script; do
  if [ "$(sed -n '1p' "$script")" = '#!/usr/bin/env bash' ]; then
    sed -i '1s|^#!/usr/bin/env bash$|#!/data/data/com.termux/files/usr/bin/env bash|' "$script"
  fi
  chmod 0755 "$script"
done < <(find "$stage/scripts" -maxdepth 1 -type f -name '*.sh' -print0 | sort -z)

payload_source_tree_sha256="$(python3 - "$stage" <<'PY'
import hashlib
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
digest = hashlib.sha256()
paths = [path for path in root.rglob("*") if path.is_file() and path.relative_to(root).as_posix() != "service-manager"]
for path in sorted(paths, key=lambda item: item.relative_to(root).as_posix()):
    relative = path.relative_to(root).as_posix().encode()
    digest.update(len(relative).to_bytes(4, "big"))
    digest.update(relative)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
print(digest.hexdigest())
PY
)"

mkdir -p "$stage/metadata"
python3 - \
  "$stage/metadata/build.json" "$source_commit" "$source_dirty" \
  "$source_status_sha256" "$source_tree_sha256" "$source_tree_id" \
  "$payload_source_tree_sha256" "$binary_sha256" "$RUST_TARGET" \
  "$RUST_TOOLCHAIN" "$ANDROID_API_LEVEL" "$NDK_VERSION" "$RUSTC_VERSION" \
  "$CARGO_VERSION" "$LINKER_PATH" "$BUILD_COMMAND" <<'PY'
import json
import sys

(
    output_path,
    source_commit,
    source_dirty,
    source_status_sha,
    source_tree_sha,
    source_tree_id,
    payload_source_tree_sha,
    binary_sha,
    rust_target,
    rust_toolchain,
    android_api_level,
    ndk_version,
    rustc_version,
    cargo_version,
    linker_path,
    build_command,
) = sys.argv[1:]
metadata = {
    "component": "service-manager",
    "version": "0.3.3",
    "sourceRepo": "https://github.com/jiwuyou/service-manager",
    "sourceCommit": source_commit,
    "sourceDirty": source_dirty == "true",
    "sourceStatusSha256": source_status_sha,
    "sourceTreeSha256": source_tree_sha,
    "sourceTreeId": source_tree_id,
    "payloadSourceTreeSha256": payload_source_tree_sha,
    "binarySha256": binary_sha,
    "buildToolchain": {
        "method": "local-android-ndk-cross",
        "rustTarget": rust_target,
        "rustToolchain": rust_toolchain,
        "androidApiLevel": int(android_api_level),
        "androidNdkVersion": ndk_version,
        "rustc": rustc_version,
        "cargo": cargo_version,
        "linker": linker_path,
        "buildCommand": build_command,
    },
}
with open(output_path, "w", encoding="utf-8") as handle:
    json.dump(metadata, handle, ensure_ascii=False, indent=2, sort_keys=True)
    handle.write("\n")
PY

find "$stage" -exec touch -h -d '@0' {} +
archive="$PAYLOAD_DIR/service-manager.tar"
temporary="$archive.tmp.$$"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner --format=gnu \
  -cf "$temporary" -C "$stage" .
mv "$temporary" "$archive"
archive_sha256="$(sha256sum "$archive" | awk '{print $1}')"
archive_size="$(stat -c '%s' "$archive")"

python3 - \
  "$PAYLOAD_DIR" "$archive_sha256" "$archive_size" "$binary_sha256" \
  "$source_commit" "$source_dirty" "$source_status_sha256" "$source_tree_sha256" \
  "$source_tree_id" "$payload_source_tree_sha256" "$RUST_TARGET" "$RUST_TOOLCHAIN" \
  "$ANDROID_API_LEVEL" "$NDK_VERSION" "$RUSTC_VERSION" "$CARGO_VERSION" \
  "$LINKER_PATH" "$BUILD_COMMAND" <<'PY'
import json
import os
import sys

(
    payload_dir,
    archive_sha,
    archive_size,
    binary_sha,
    source_commit,
    source_dirty,
    source_status_sha,
    source_tree_sha,
    source_tree_id,
    payload_source_tree_sha,
    rust_target,
    rust_toolchain,
    android_api_level,
    ndk_version,
    rustc_version,
    cargo_version,
    linker_path,
    build_command,
) = sys.argv[1:]
values = {
    "sha256": archive_sha,
    "size": int(archive_size),
    "binarySha256": binary_sha,
    "platform": "termux-arm64",
    "buildPlatform": "android-arm64",
    "binaryInterpreter": "/system/bin/linker64",
    "version": "0.3.3",
    "sourceRepo": "https://github.com/jiwuyou/service-manager",
    "sourceCommit": source_commit,
    "sourceDirty": source_dirty == "true",
    "sourceStatusSha256": source_status_sha,
    "sourceTreeSha256": source_tree_sha,
    "sourceTreeId": source_tree_id,
    "payloadSourceTreeSha256": payload_source_tree_sha,
    "buildToolchain": {
        "method": "local-android-ndk-cross",
        "rustTarget": rust_target,
        "rustToolchain": rust_toolchain,
        "androidApiLevel": int(android_api_level),
        "androidNdkVersion": ndk_version,
        "rustc": rustc_version,
        "cargo": cargo_version,
        "linker": linker_path,
        "buildCommand": build_command,
    },
}
for manifest_name, array_name in (("manifest.json", "components"), ("payload-manifest.json", "payloads")):
    path = os.path.join(payload_dir, manifest_name)
    document = json.load(open(path, encoding="utf-8"))
    entry = next(item for item in document[array_name] if item.get("id") == "service-manager")
    entry.update(values)
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(document, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    os.replace(temporary, path)
PY

verify_payload "$PAYLOAD_DIR"
printf 'built service-manager payload: %s\n' "$archive"
printf 'source tree: %s dirty=%s base=%s\n' "$source_tree_id" "$source_dirty" "$source_commit"
printf 'binary: %s sha256=%s\n' "$BINARY" "$binary_sha256"
printf 'toolchain: NDK %s API %s, Rust %s, target %s\n' "$NDK_VERSION" "$ANDROID_API_LEVEL" "$RUST_TOOLCHAIN" "$RUST_TARGET"
