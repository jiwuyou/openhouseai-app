#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
PAYLOAD_DIR="$REPO_ROOT/app/src/main/assets/openhouse/product-payloads"
VALIDATOR="$REPO_ROOT/scripts/validate-openhouse-payloads.sh"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
fixture="$work_dir/product-payloads"
stage="$work_dir/service-manager"
mkdir -p "$fixture" "$stage"
cp -al "$PAYLOAD_DIR/." "$fixture/"

write_archive() {
  local layout="$1"
  local runtime="${2:-termux}"
  rm -rf "$stage"
  mkdir -p "$stage"

  python3 - "$stage/service-manager" "$runtime" <<'PY'
import pathlib
import sys

binary = bytearray(128)
binary[:4] = b"\x7fELF"
binary[4] = 2  # ELFCLASS64
binary[5] = 1  # ELFDATA2LSB
binary[18:20] = (183).to_bytes(2, "little")  # EM_AARCH64
interpreter = b"/system/bin/linker64\x00" if sys.argv[2] == "termux" else b"/lib/ld-linux-aarch64.so.1\x00"
binary[64:64 + len(interpreter)] = interpreter
pathlib.Path(sys.argv[1]).write_bytes(binary)
PY
  chmod 755 "$stage/service-manager"

  if [ "$layout" = repo ]; then
    mkdir -p "$stage/scripts"
    printf '#!/usr/bin/env sh\nexit 0\n' > "$stage/scripts/install.sh"
    printf '#!/usr/bin/env sh\nexit 0\n' > "$stage/scripts/check.sh"
    chmod 755 "$stage/scripts/install.sh" "$stage/scripts/check.sh"
  fi

  rm -f "$fixture/service-manager.tar"
  if [ "$layout" = repo ]; then
    tar -cf "$fixture/service-manager.tar" -C "$stage" service-manager scripts
  else
    tar -cf "$fixture/service-manager.tar" -C "$stage" service-manager
  fi

  python3 - "$fixture" "$stage/service-manager" <<'PY'
import hashlib
import json
import os
import sys

payload_dir, binary_path = sys.argv[1:]
archive_path = os.path.join(payload_dir, "service-manager.tar")

def digest(path):
    value = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()

for manifest_name, key in (
    ("manifest.json", "components"),
    ("payload-manifest.json", "payloads"),
):
    path = os.path.join(payload_dir, manifest_name)
    with open(path, "r", encoding="utf-8") as handle:
        document = json.load(handle)
    entry = next(item for item in document[key] if item.get("id") == "service-manager")
    entry["sha256"] = digest(archive_path)
    entry["size"] = os.path.getsize(archive_path)
    entry["binarySha256"] = digest(binary_path)
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(document, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    os.replace(temporary, path)
PY
}

write_archive repo
"$VALIDATOR" "$fixture" > "$work_dir/repo.log" 2>&1 \
  || { cat "$work_dir/repo.log" >&2; fail 'repo-style service-manager archive was rejected'; }

write_archive repo glibc
if "$VALIDATOR" "$fixture" > "$work_dir/glibc.log" 2>&1; then
  fail 'glibc service-manager binary was accepted as termux-arm64'
fi
grep -Fq 'service-manager termux-arm64 binary must use /system/bin/linker64' "$work_dir/glibc.log" \
  || { cat "$work_dir/glibc.log" >&2; fail 'glibc interpreter rejection was not reported'; }

write_archive raw
if "$VALIDATOR" "$fixture" > "$work_dir/raw.log" 2>&1; then
  fail 'raw single-binary service-manager archive was accepted'
fi
grep -Fq 'service-manager.tar is missing non-empty scripts/install.sh' "$work_dir/raw.log" \
  || { cat "$work_dir/raw.log" >&2; fail 'missing install.sh was not reported'; }
grep -Fq 'service-manager.tar is missing non-empty scripts/check.sh' "$work_dir/raw.log" \
  || { cat "$work_dir/raw.log" >&2; fail 'missing check.sh was not reported'; }

printf 'service-manager payload validation focused tests passed\n'
