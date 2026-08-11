#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
market_url="${WUXIANPI_RESCUE_MARKET_URL:-https://wuxianpirescue.webefficacy.com}"
resource_set_path="$repo_dir/app/src/main/assets/openhouse/product-payloads/resource-set.json"
publish_manifest_path="$repo_dir/distribution/resources-v2/publish-manifest.json"
report_path="$repo_dir/build/reports/apk-build/resource-alignment.json"
timeout_seconds="${WUXIANPI_RESCUE_ALIGNMENT_TIMEOUT_SECONDS:-60}"
request_attempts="${WUXIANPI_RESCUE_ALIGNMENT_ATTEMPTS:-3}"

usage() {
  cat <<'EOF'
Usage: scripts/check-production-resource-alignment.sh [options]

Verify that the APK's canonical V2 resource set and all resource bytes match
the active WuxianPi Rescue market releases.

Options:
  --market-url URL          Rescue market origin
  --repository-root DIR     Root used to resolve publish-manifest paths
  --resource-set FILE       Local canonical resource-set.json
  --publish-manifest FILE   Local V2 publish-manifest.json
  --report FILE             Successful alignment report destination
  --timeout SECONDS         Per-request timeout (default: 60)
  --attempts COUNT          Attempts for transient request failures (default: 3)
  -h, --help                Show this help
EOF
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --market-url) market_url="${2:-}"; shift 2 ;;
    --repository-root) repo_dir="${2:-}"; shift 2 ;;
    --resource-set) resource_set_path="${2:-}"; shift 2 ;;
    --publish-manifest) publish_manifest_path="${2:-}"; shift 2 ;;
    --report) report_path="${2:-}"; shift 2 ;;
    --timeout) timeout_seconds="${2:-}"; shift 2 ;;
    --attempts) request_attempts="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

for command in python3; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Production resource alignment requires %s\n' "$command" >&2
    exit 2
  }
done
[[ -s "$resource_set_path" ]] || { printf 'Missing local resource set: %s\n' "$resource_set_path" >&2; exit 2; }
[[ -s "$publish_manifest_path" ]] || { printf 'Missing V2 publish manifest: %s\n' "$publish_manifest_path" >&2; exit 2; }
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] \
  || { printf 'Alignment timeout must be a positive integer: %s\n' "$timeout_seconds" >&2; exit 2; }
[[ "$request_attempts" =~ ^[1-9][0-9]*$ ]] \
  || { printf 'Alignment attempts must be a positive integer: %s\n' "$request_attempts" >&2; exit 2; }

# A stale success report must never survive a failed check.
rm -f -- "$report_path"

python3 - \
  "$repo_dir" \
  "$market_url" \
  "$resource_set_path" \
  "$publish_manifest_path" \
  "$report_path" \
  "$timeout_seconds" \
  "$request_attempts" <<'PY'
import datetime
import hashlib
import json
import os
import pathlib
import ssl
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

(
    repo_dir_value,
    market_url_value,
    resource_set_value,
    publish_manifest_value,
    report_value,
    timeout_value,
    request_attempts_value,
) = sys.argv[1:]

repo_dir = pathlib.Path(repo_dir_value).resolve()
resource_set_path = pathlib.Path(resource_set_value).resolve()
publish_manifest_path = pathlib.Path(publish_manifest_value).resolve()
report_path = pathlib.Path(report_value).resolve()
timeout_seconds = int(timeout_value)
request_attempts = int(request_attempts_value)
required_resource_ids = {
    "service-manager",
    "openhouse-control-plane",
    "openhouse-runtime",
    "wuyou",
    "openhouse-web",
}


def fail(message):
    raise SystemExit(f"Production resource alignment failed: {message}")


def load_json(path, label):
    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {label} {path}: {error}")


def canonical_digest(value):
    encoded = json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def normalized_origin(url, label):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
        fail(f"{label} must be an HTTP(S) URL without credentials: {url}")
    try:
        port = parsed.port
    except ValueError as error:
        fail(f"{label} has an invalid port: {error}")
    effective_port = port if port is not None else (443 if parsed.scheme == "https" else 80)
    return parsed.scheme.lower(), parsed.hostname.lower(), effective_port


market_url = market_url_value.rstrip("/")
trusted_origin = normalized_origin(market_url, "market URL")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        fail(f"market request redirected to {new_url}; redirects are not trusted")


opener = urllib.request.build_opener(
    NoRedirect(),
    urllib.request.HTTPSHandler(context=ssl.create_default_context()),
)


def trusted_url(value, label):
    resolved = urllib.parse.urljoin(f"{market_url}/", value)
    if normalized_origin(resolved, label) != trusted_origin:
        fail(f"{label} leaves trusted market origin: {resolved}")
    return resolved


def request(url, label):
    target = trusted_url(url, label)
    last_error = None
    for attempt in range(1, request_attempts + 1):
        try:
            return opener.open(
                urllib.request.Request(target, headers={"Accept": "application/json", "User-Agent": "openhouse-resource-alignment/1"}),
                timeout=timeout_seconds,
            )
        except urllib.error.HTTPError as error:
            if error.code not in {408, 425, 429, 500, 502, 503, 504}:
                fail(f"{label} returned HTTP {error.code}: {target}")
            last_error = f"HTTP {error.code}"
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = str(error)
        if attempt < request_attempts:
            time.sleep(min(2 ** (attempt - 1), 4))
    fail(f"cannot reach {label} at {target} after {request_attempts} attempts: {last_error}")


def fetch_json(path, label):
    try:
        with request(path, label) as response:
            if response.status != 200:
                fail(f"{label} returned HTTP {response.status}")
            return json.load(response)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"{label} returned invalid JSON: {error}")
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        fail(f"cannot read {label}: {error}")


def require_string(value, label):
    if not isinstance(value, str) or not value:
        fail(f"{label} must be a non-empty string")
    return value


def members_by_id(value, label):
    if not isinstance(value, list):
        fail(f"{label} must be an array")
    result = {}
    for item in value:
        if not isinstance(item, dict):
            fail(f"{label} contains a non-object entry")
        resource_id = require_string(item.get("id"), f"{label}.id")
        if resource_id in result:
            fail(f"{label} contains duplicate resource {resource_id}")
        result[resource_id] = item
    return result


local_set = load_json(resource_set_path, "local resource set")
if local_set.get("schema") != 2:
    fail("local resource set schema must be 2")
set_id = require_string(local_set.get("id"), "local resource set id")
local_set_version = require_string(local_set.get("version"), "local resource set version")
local_sequence = local_set.get("sequence")
if not isinstance(local_sequence, int) or isinstance(local_sequence, bool):
    fail("local resource set sequence must be an integer")
local_members = members_by_id(local_set.get("resources"), "local resource set resources")
if set(local_members) != required_resource_ids:
    fail(f"local resource set must contain exactly {sorted(required_resource_ids)}")

publish_manifest = load_json(publish_manifest_path, "V2 publish manifest")
if publish_manifest.get("schema") != 2 or publish_manifest.get("market") != "rescue":
    fail("V2 publish manifest must be schema 2 for the rescue market")
published = members_by_id(publish_manifest.get("resources"), "publish manifest resources")
if set(published) != required_resource_ids:
    fail(f"publish manifest must contain exactly {sorted(required_resource_ids)}")
publish_set = publish_manifest.get("resourceSet")
if not isinstance(publish_set, dict) or publish_set.get("id") != set_id or publish_set.get("version") != local_set_version:
    fail("publish manifest resource set does not match the local canonical set")

local_releases = {}
for resource_id in sorted(required_resource_ids):
    publication = published[resource_id]
    member = local_members[resource_id]
    if publication.get("version") != member.get("version"):
        fail(f"publish manifest version differs for {resource_id}")
    archive_value = require_string(publication.get("archivePath"), f"{resource_id} archivePath")
    metadata_value = require_string(publication.get("metadataPath"), f"{resource_id} metadataPath")
    archive_path = (repo_dir / archive_value).resolve()
    metadata_path = (repo_dir / metadata_value).resolve()
    try:
        archive_path.relative_to(repo_dir)
        metadata_path.relative_to(repo_dir)
    except ValueError:
        fail(f"publish manifest path escapes repository for {resource_id}")
    if not archive_path.is_file():
        fail(f"local archive is missing for {resource_id}: {archive_path}")
    metadata = load_json(metadata_path, f"{resource_id} metadata")
    data_size = archive_path.stat().st_size
    digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
    expected = {
        "id": resource_id,
        "version": member.get("version"),
        "sha256": member.get("sha256"),
    }
    if any(metadata.get(key) != value for key, value in expected.items()):
        fail(f"local metadata does not match resource set for {resource_id}")
    if metadata.get("size") != data_size or metadata.get("sha256") != digest:
        fail(f"local metadata size/SHA-256 does not match archive for {resource_id}")
    local_releases[resource_id] = {
        "archivePath": archive_path,
        "metadata": metadata,
        "size": data_size,
        "sha256": digest,
    }

resources_before = fetch_json("/api/v2/resources", "resource catalog")
set_before = fetch_json(f"/api/v2/resource-sets/{urllib.parse.quote(set_id, safe='')}", "resource set catalog")
if resources_before.get("schema") != 2:
    fail("remote resource catalog schema must be 2")
resource_revision_before = require_string(resources_before.get("revision"), "remote resource catalog revision")
resource_snapshot_before = canonical_digest(resources_before.get("resources"))

if not isinstance(set_before, dict) or set_before.get("id") != set_id:
    fail(f"remote resource set {set_id} is missing")
remote_latest_set_version = require_string(set_before.get("latestVersion"), "remote resource set latestVersion")
remote_set_versions = set_before.get("versions")
if not isinstance(remote_set_versions, list):
    fail("remote resource set versions must be an array")
remote_set_matches = [item for item in remote_set_versions if isinstance(item, dict) and item.get("version") == remote_latest_set_version]
if len(remote_set_matches) != 1:
    fail("remote resource set latestVersion does not resolve to exactly one release")
remote_set = remote_set_matches[0]
remote_sequence = remote_set.get("sequence")
if not isinstance(remote_sequence, int) or isinstance(remote_sequence, bool):
    fail("remote resource set sequence must be an integer")
if remote_sequence > local_sequence:
    fail(f"production resource set is newer than APK source: remote={remote_sequence} local={local_sequence}")
if remote_sequence < local_sequence:
    fail(f"APK source resource set is newer than production: local={local_sequence} remote={remote_sequence}")
if remote_latest_set_version != local_set_version:
    fail(f"resource set version differs at sequence {local_sequence}: remote={remote_latest_set_version} local={local_set_version}")
for field in ("schema", "id", "version", "sequence", "abi", "minApkVersionCode"):
    if remote_set.get(field) != local_set.get(field):
        fail(f"resource set field differs: {field}")
remote_members = members_by_id(remote_set.get("resources"), "remote resource set resources")
if set(remote_members) != required_resource_ids:
    fail(f"remote resource set must contain exactly {sorted(required_resource_ids)}")
for resource_id in required_resource_ids:
    for field in ("id", "version", "sha256"):
        if remote_members[resource_id].get(field) != local_members[resource_id].get(field):
            fail(f"resource set member differs for {resource_id}: {field}")

remote_resources = members_by_id(resources_before.get("resources"), "remote resource catalog resources")
if not required_resource_ids.issubset(remote_resources):
    missing = sorted(required_resource_ids - set(remote_resources))
    fail(f"remote resource catalog is missing: {', '.join(missing)}")

verified_resources = []
with tempfile.TemporaryDirectory(prefix="openhouse-resource-alignment-") as temporary:
    temporary_path = pathlib.Path(temporary)
    for resource_id in sorted(required_resource_ids):
        remote_catalog_entry = remote_resources[resource_id]
        local_member = local_members[resource_id]
        expected_version = local_member.get("version")
        if remote_catalog_entry.get("latestVersion") != expected_version:
            fail(
                f"production latestVersion differs for {resource_id}: "
                f"remote={remote_catalog_entry.get('latestVersion')} local={expected_version}"
            )
        versions = remote_catalog_entry.get("versions")
        if not isinstance(versions, list):
            fail(f"remote versions must be an array for {resource_id}")
        matches = [item for item in versions if isinstance(item, dict) and item.get("version") == expected_version]
        if len(matches) != 1:
            fail(f"production version {expected_version} does not resolve exactly once for {resource_id}")
        release = matches[0]
        local_release = local_releases[resource_id]
        local_metadata = local_release["metadata"]
        if release.get("id") != resource_id:
            fail(f"remote release ID differs for {resource_id}")
        for field in ("archive", "compression", "abi", "minApkVersionCode", "maxApkVersionCode"):
            if release.get(field) != local_metadata.get(field):
                fail(f"production metadata differs for {resource_id}: {field}")
        if release.get("url") != local_metadata.get("url"):
            fail(f"production download path differs for {resource_id}")
        if release.get("sha256") != local_member.get("sha256") or release.get("sha256") != local_release["sha256"]:
            fail(f"production SHA-256 differs for {resource_id}")
        if release.get("size") != local_release["size"]:
            fail(f"production archive size differs for {resource_id}")
        archive_url = trusted_url(require_string(release.get("url"), f"{resource_id} download URL"), f"{resource_id} download URL")
        destination = temporary_path / f"{resource_id}.tgz"
        digest = hashlib.sha256()
        actual_size = 0
        try:
            with request(archive_url, f"{resource_id} archive") as response, destination.open("wb") as output:
                if response.status != 200:
                    fail(f"{resource_id} archive returned HTTP {response.status}")
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    actual_size += len(chunk)
                    if actual_size > local_release["size"]:
                        fail(f"downloaded archive exceeds declared size for {resource_id}")
                    digest.update(chunk)
                    output.write(chunk)
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            fail(f"cannot download {resource_id} archive: {error}")
        actual_digest = digest.hexdigest()
        if actual_size != release.get("size"):
            fail(f"downloaded archive size differs for {resource_id}: actual={actual_size} expected={release.get('size')}")
        if actual_digest != release.get("sha256"):
            fail(f"downloaded archive SHA-256 differs for {resource_id}")
        verified_resources.append({
            "id": resource_id,
            "version": expected_version,
            "archive": release.get("archive"),
            "size": actual_size,
            "sha256": actual_digest,
            "downloadUrl": archive_url,
        })

resources_after = fetch_json("/api/v2/resources", "resource catalog after downloads")
set_after = fetch_json(f"/api/v2/resource-sets/{urllib.parse.quote(set_id, safe='')}", "resource set catalog after downloads")
resource_revision_after = require_string(resources_after.get("revision"), "final remote resource catalog revision")
resource_snapshot_after = canonical_digest(resources_after.get("resources"))
if resource_revision_after != resource_revision_before:
    fail(
        "production resource catalog changed during validation: "
        f"before={resource_revision_before} after={resource_revision_after}"
    )
if resource_snapshot_after != resource_snapshot_before:
    fail("production resource catalog contents changed during validation")
set_snapshot_before = canonical_digest(set_before)
set_snapshot_after = canonical_digest(set_after)
if set_snapshot_after != set_snapshot_before:
    fail("production resource set catalog changed during validation")

report = {
    "schema": 1,
    "status": "passed",
    "checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
    "marketUrl": market_url,
    "trustedOrigin": f"{trusted_origin[0]}://{trusted_origin[1]}:{trusted_origin[2]}",
    "resourceCatalogRevisionBefore": resource_revision_before,
    "resourceCatalogRevisionAfter": resource_revision_after,
    "resourceCatalogSnapshotSha256Before": resource_snapshot_before,
    "resourceCatalogSnapshotSha256After": resource_snapshot_after,
    "resourceSetSnapshotSha256Before": set_snapshot_before,
    "resourceSetSnapshotSha256After": set_snapshot_after,
    "resourceSet": {
        "id": set_id,
        "version": local_set_version,
        "sequence": local_sequence,
    },
    "resources": verified_resources,
}
report_path.parent.mkdir(parents=True, exist_ok=True)
temporary_report = report_path.with_name(f".{report_path.name}.tmp.{os.getpid()}")
with temporary_report.open("w", encoding="utf-8") as handle:
    json.dump(report, handle, ensure_ascii=True, indent=2, sort_keys=True)
    handle.write("\n")
os.replace(temporary_report, report_path)
print(
    f"Production resources aligned: {set_id}@{local_set_version} "
    f"sequence={local_sequence} resources={len(verified_resources)}"
)
print(f"Alignment report: {report_path}")
PY
