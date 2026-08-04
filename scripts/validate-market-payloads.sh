#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
market_dir="$repo_dir/distribution/market-payloads"
catalog="$market_dir/catalog.json"

python3 - "$market_dir" "$catalog" <<'PY'
import hashlib
import json
import pathlib
import sys

market_dir = pathlib.Path(sys.argv[1])
catalog_path = pathlib.Path(sys.argv[2])
catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
if catalog.get("abi") != "arm64-v8a":
    raise SystemExit("market catalog must target arm64-v8a")
packages = catalog.get("packages")
if not isinstance(packages, list) or not packages:
    raise SystemExit("market catalog packages must be a non-empty array")
seen = set()
for entry in packages:
    package_id = entry.get("id")
    archive = entry.get("archive")
    if not package_id or package_id in seen:
        raise SystemExit(f"invalid or duplicate market package id: {package_id!r}")
    seen.add(package_id)
    if entry.get("abi") != "arm64-v8a" or entry.get("compression") != "gzip":
        raise SystemExit(f"market package {package_id} must be gzip arm64-v8a")
    path = market_dir / archive
    if not path.is_file():
        raise SystemExit(f"market package is missing: {path}")
    data = path.read_bytes()
    if len(data) != entry.get("size"):
        raise SystemExit(f"market package size mismatch: {package_id}")
    if hashlib.sha256(data).hexdigest() != entry.get("sha256"):
        raise SystemExit(f"market package checksum mismatch: {package_id}")
print(f"Market payload validation passed: {len(packages)} packages")
PY
