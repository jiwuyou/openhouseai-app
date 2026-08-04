#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(CDPATH='' cd -- "$SCRIPT_DIR/../../../.." && pwd)"
BUILDER="$REPO_ROOT/scripts/build-service-manager-payload.sh"
SOURCE_REPO="/root/projects/service-manager"
PAYLOAD_DIR="$REPO_ROOT/app/src/main/assets/openhouse/product-payloads"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

[ -x "$BUILDER" ] || fail 'payload builder is missing or not executable'
[ -f "$SOURCE_REPO/Cargo.toml" ] || fail 'service-manager source repository is missing'

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT INT HUP TERM

# A valid Android AArch64 ELF is not sufficient identity. Production mode must
# reject external binary substitution and build the authoritative Cargo package.
ndk="${ANDROID_NDK_HOME:-/root/android-sdk/ndk/29.0.14206865}"
api="${ANDROID_API_LEVEL:-24}"
linker="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android${api}-clang"
[ -x "$linker" ] || fail "Android NDK linker is missing: $linker"
printf '%s\n' 'int main(void) { return 0; }' > "$work_dir/minimal.c"
"$linker" "$work_dir/minimal.c" -o "$work_dir/generic-android-aarch64"
file "$work_dir/generic-android-aarch64" | grep -Eq 'ELF 64-bit LSB.*(ARM aarch64|aarch64)' \
  || fail 'negative-test binary is not Android AArch64 ELF64'
readelf -l "$work_dir/generic-android-aarch64" | grep -Fq 'Requesting program interpreter: /system/bin/linker64' \
  || fail 'negative-test binary is not bionic-linked'
if "$BUILDER" --binary "$work_dir/generic-android-aarch64" > "$work_dir/external-binary.log" 2>&1; then
  fail 'production builder accepted arbitrary Android AArch64 ELF substitution'
fi
grep -Fq 'unsupported argument: --binary' "$work_dir/external-binary.log" \
  || { cat "$work_dir/external-binary.log" >&2; fail 'external binary rejection was not explicit'; }

cargo_output="$SOURCE_REPO/target/service-manager-payload-build/aarch64-linux-android/release/service-manager"
generic_sha="$(sha256sum "$work_dir/generic-android-aarch64" | awk '{print $1}')"
mkdir -p "$(dirname "$cargo_output")"
cp "$work_dir/generic-android-aarch64" "$cargo_output"
"$BUILDER" --build-local
[ "$(sha256sum "$cargo_output" | awk '{print $1}')" != "$generic_sha" ] \
  || fail 'builder packaged the substituted generic canonical target instead of relinking service-manager'
first_sha="$(sha256sum "$PAYLOAD_DIR/service-manager.tgz" | awk '{print $1}')"
cp "$PAYLOAD_DIR/service-manager.tgz" "$work_dir/first.tgz"
"$BUILDER" --build-local
second_sha="$(sha256sum "$PAYLOAD_DIR/service-manager.tgz" | awk '{print $1}')"
[ "$first_sha" = "$second_sha" ] || fail 'identical authoritative builds were not deterministic'
cmp -s "$work_dir/first.tgz" "$PAYLOAD_DIR/service-manager.tgz" \
  || fail 'identical authoritative builds produced byte-different payloads'

"$BUILDER" --verify-only
tar -tzf "$PAYLOAD_DIR/service-manager.tgz" > "$work_dir/archive-list.txt"
grep -Eq '^\./metadata/build\.json$' "$work_dir/archive-list.txt" \
  || fail 'payload build metadata is missing'

python3 - "$SOURCE_REPO" "$PAYLOAD_DIR" <<'PY'
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tarfile

source_repo = pathlib.Path(sys.argv[1]).resolve()
payload_dir = pathlib.Path(sys.argv[2]).resolve()
excluded_roots = {".git", ".agents", ".codex", "target", "dist"}
excluded_files = {"service-manager"}

digest = hashlib.sha256()
paths = []
for path in source_repo.rglob("*"):
    relative = path.relative_to(source_repo)
    if not path.is_file() or relative.parts[0] in excluded_roots or relative.as_posix() in excluded_files:
        continue
    paths.append(path)
for path in sorted(paths, key=lambda item: item.relative_to(source_repo).as_posix()):
    relative = path.relative_to(source_repo).as_posix().encode()
    digest.update(len(relative).to_bytes(4, "big"))
    digest.update(relative)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
source_tree_sha = digest.hexdigest()
source_tree_id = f"sha256:{source_tree_sha}"
source_commit = subprocess.check_output(
    ["git", "-C", str(source_repo), "rev-parse", "HEAD"], text=True
).strip()
source_status = subprocess.check_output(
    ["git", "-C", str(source_repo), "status", "--porcelain=v1", "--untracked-files=all"]
)
source_status = source_status.rstrip(b"\n")
source_status_sha = hashlib.sha256(source_status).hexdigest()
source_dirty = bool(source_status)
binary_path = source_repo / "target/service-manager-payload-build/aarch64-linux-android/release/service-manager"
binary_sha = hashlib.sha256(binary_path.read_bytes()).hexdigest()
archive_path = payload_dir / "service-manager.tgz"

with tarfile.open(archive_path, "r:*") as archive:
    members = {
        (member.name[2:] if member.name.startswith("./") else member.name): member
        for member in archive.getmembers()
    }
    metadata = json.loads(archive.extractfile(members["metadata/build.json"]).read())
    payload_binary = archive.extractfile(members["service-manager"]).read()
    cargo_toml = archive.extractfile(members["Cargo.toml"]).read().decode()

assert metadata["component"] == "service-manager"
assert metadata["version"] == "0.3.4"
assert metadata["sourceCommit"] == source_commit
assert metadata["sourceDirty"] is source_dirty
assert metadata["sourceStatusSha256"] == source_status_sha
assert metadata["sourceTreeSha256"] == source_tree_sha
assert metadata["sourceTreeId"] == source_tree_id
assert metadata["binarySha256"] == binary_sha
assert hashlib.sha256(payload_binary).hexdigest() == binary_sha
assert 'name = "service-manager"' in cargo_toml
assert 'version = "0.3.4"' in cargo_toml

for manifest_name, array_name in (("manifest.json", "components"), ("payload-manifest.json", "payloads")):
    document = json.loads((payload_dir / manifest_name).read_text())
    entry = next(item for item in document[array_name] if item.get("id") == "service-manager")
    assert entry["version"] == "0.3.4"
    assert entry["sourceCommit"] == source_commit
    assert entry["sourceDirty"] is source_dirty
    assert entry["sourceStatusSha256"] == source_status_sha
    assert entry["sourceTreeSha256"] == source_tree_sha
    assert entry["sourceTreeId"] == source_tree_id
    assert entry["binarySha256"] == binary_sha
PY

# Mutate the embedded and manifest provenance consistently, while preserving a
# valid archive hash/size. Verification must still reject it as stale against
# the exact current authoritative source tree.
fixture="$work_dir/stale-payload"
stage="$work_dir/stale-stage"
mkdir -p "$fixture" "$stage"
cp "$PAYLOAD_DIR/manifest.json" "$PAYLOAD_DIR/payload-manifest.json" "$fixture/"
tar -xzf "$PAYLOAD_DIR/service-manager.tgz" -C "$stage"
python3 - "$stage/metadata/build.json" "$fixture" <<'PY'
import json
import pathlib
import sys

metadata_path = pathlib.Path(sys.argv[1])
fixture = pathlib.Path(sys.argv[2])
metadata = json.loads(metadata_path.read_text())
metadata["sourceTreeSha256"] = "0" * 64
metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
for name, array_name in (("manifest.json", "components"), ("payload-manifest.json", "payloads")):
    path = fixture / name
    document = json.loads(path.read_text())
    entry = next(item for item in document[array_name] if item.get("id") == "service-manager")
    entry["sourceTreeSha256"] = "0" * 64
    path.write_text(json.dumps(document, indent=2) + "\n")
PY
find "$stage" -exec touch -h -d '@0' {} +
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner --format=gnu \
  -cf - -C "$stage" . | gzip -n > "$fixture/service-manager.tgz"
fixture_sha="$(sha256sum "$fixture/service-manager.tgz" | awk '{print $1}')"
fixture_size="$(stat -c '%s' "$fixture/service-manager.tgz")"
python3 - "$fixture" "$fixture_sha" "$fixture_size" <<'PY'
import json
import pathlib
import sys

fixture = pathlib.Path(sys.argv[1])
archive_sha = sys.argv[2]
archive_size = int(sys.argv[3])
for name, array_name in (("manifest.json", "components"), ("payload-manifest.json", "payloads")):
    path = fixture / name
    document = json.loads(path.read_text())
    entry = next(item for item in document[array_name] if item.get("id") == "service-manager")
    entry["sha256"] = archive_sha
    entry["size"] = archive_size
    path.write_text(json.dumps(document, indent=2) + "\n")
PY
if "$BUILDER" --verify-only --payload-dir "$fixture" > "$work_dir/stale.log" 2>&1; then
  fail 'stale source-tree provenance was accepted'
fi
grep -Fq 'payload build metadata sourceTreeSha256 is stale' "$work_dir/stale.log" \
  || { cat "$work_dir/stale.log" >&2; fail 'stale source-tree rejection was not explicit'; }

printf 'service-manager payload identity, provenance, and determinism tests passed\n'
