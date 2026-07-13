#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PAYLOAD_DIR="${1:-$REPO_DIR/app/src/main/assets/openhouse/product-payloads}"

if ! command -v python3 >/dev/null 2>&1; then
  printf 'validate-openhouse-payloads: python3 is required for JSON/tar validation.\n' >&2
  exit 2
fi

python3 - "$PAYLOAD_DIR" <<'PY'
import hashlib
import json
import os
import re
import sys
import tarfile

payload_dir = os.path.abspath(sys.argv[1])
manifest_path = os.path.join(payload_dir, "manifest.json")
payload_manifest_path = os.path.join(payload_dir, "payload-manifest.json")

errors = []
warnings = []
digest_cache = {}


def fail(message):
    errors.append(message)


def warn(message):
    warnings.append(message)


def load_json(path):
    if not os.path.isfile(path):
        fail(f"missing manifest: {path}")
        return {}
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except Exception as exc:
        fail(f"invalid JSON {path}: {exc}")
        return {}


def component_array(payload, key):
    value = payload.get(key)
    if isinstance(value, list):
        return value
    fail(f"{key} must be an array in {payload_dir}")
    return []


def by_id(entries, source):
    result = {}
    for entry in entries:
        if not isinstance(entry, dict):
            fail(f"{source} contains a non-object entry")
            continue
        component_id = str(entry.get("id") or "").strip()
        if not component_id:
            fail(f"{source} contains an entry without id")
            continue
        if component_id in result:
            fail(f"{source} has duplicate id {component_id}")
        result[component_id] = entry
    return result


def file_digest(path):
    if path not in digest_cache:
        digest = hashlib.sha256()
        size = 0
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
                size += len(chunk)
        digest_cache[path] = (size, digest.hexdigest())
    return digest_cache[path]


def validate_archive(entry, source):
    component_id = entry.get("id")
    archive = entry.get("archive")
    expected_sha = str(entry.get("sha256") or "").lower()
    expected_size = entry.get("size")
    if not isinstance(archive, str) or not archive:
        fail(f"{source}:{component_id} missing archive")
        return
    archive_path = os.path.join(payload_dir, archive)
    if not os.path.isfile(archive_path):
        fail(f"{source}:{component_id} archive does not exist: {archive}")
        return
    actual_size, actual_sha = file_digest(archive_path)
    if actual_size <= 0:
        fail(f"{source}:{component_id} archive is empty: {archive}")
    if not isinstance(expected_size, int):
        fail(f"{source}:{component_id} size must be an integer")
    elif actual_size != expected_size:
        fail(f"{source}:{component_id} size mismatch for {archive}: expected {expected_size}, actual {actual_size}")
    if not re.fullmatch(r"[0-9a-f]{64}", expected_sha):
        fail(f"{source}:{component_id} sha256 must be a lowercase 64-char hex digest")
    elif actual_sha != expected_sha:
        fail(f"{source}:{component_id} sha256 mismatch for {archive}: expected {expected_sha}, actual {actual_sha}")


def compare_entries(left, right, component_id):
    for field in (
        "archive",
        "sha256",
        "size",
        "binarySha256",
        "binarySize",
        "version",
        "platform",
        "registryApiVersion",
        "requires",
        "provides",
    ):
        if field in left and field in right and left.get(field) != right.get(field):
            fail(f"manifest disagreement for {component_id}.{field}: manifest.json={left.get(field)!r}, payload-manifest.json={right.get(field)!r}")


def require_positive_int(value, source):
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        fail(f"{source} must be a positive integer")
        return None
    return value


def require_true(value, source):
    if value is not True:
        fail(f"{source} must be true")


def manifest_registry_api(payload, source):
    version = require_positive_int(payload.get("registryApiVersion"), f"{source}.registryApiVersion")
    compatibility = payload.get("compatibility")
    if not isinstance(compatibility, dict):
        fail(f"{source}.compatibility must be an object")
        return version
    compat_version = require_positive_int(
        compatibility.get("registryApiVersion"),
        f"{source}.compatibility.registryApiVersion",
    )
    if version is not None and compat_version is not None and version != compat_version:
        fail(f"{source} registryApiVersion mismatch: top-level={version}, compatibility={compat_version}")
    service_manager_range = str(compatibility.get("serviceManager") or "").strip()
    if not service_manager_range:
        fail(f"{source}.compatibility.serviceManager must declare the supported service-manager range")
    require_true(compatibility.get("requiresRegistryApply"), f"{source}.compatibility.requiresRegistryApply")
    require_true(compatibility.get("requiresStableServiceIds"), f"{source}.compatibility.requiresStableServiceIds")
    return version


def service_manager_registry_api(entry, source, expected_api):
    if not isinstance(entry, dict):
        fail(f"{source}:service-manager must be an object")
        return None
    version = str(entry.get("version") or "").strip()
    if not version:
        fail(f"{source}:service-manager must declare version")
    direct_api = require_positive_int(
        entry.get("registryApiVersion"),
        f"{source}:service-manager.registryApiVersion",
    )
    provides = entry.get("provides")
    if not isinstance(provides, dict):
        fail(f"{source}:service-manager.provides must be an object")
        return direct_api
    provided_api = require_positive_int(
        provides.get("registryApiVersion"),
        f"{source}:service-manager.provides.registryApiVersion",
    )
    require_true(provides.get("registryApply"), f"{source}:service-manager.provides.registryApply")
    require_true(provides.get("stableServiceIds"), f"{source}:service-manager.provides.stableServiceIds")
    if direct_api is not None and provided_api is not None and direct_api != provided_api:
        fail(f"{source}:service-manager registryApiVersion mismatch: direct={direct_api}, provides={provided_api}")
    for observed, label in ((direct_api, "direct"), (provided_api, "provides")):
        if expected_api is not None and observed is not None and observed != expected_api:
            fail(f"{source}:service-manager {label} registryApiVersion {observed} does not match manifest {expected_api}")
    return provided_api or direct_api


def component_registry_requirement(entry, source, expected_api):
    if not isinstance(entry, dict):
        fail(f"{source} must be an object")
        return
    requires = entry.get("requires")
    if not isinstance(requires, dict):
        fail(f"{source}.requires must be an object")
        return
    service_manager_range = str(requires.get("serviceManager") or "").strip()
    if not service_manager_range:
        fail(f"{source}.requires.serviceManager must declare the supported service-manager range")
    required_api = require_positive_int(
        requires.get("registryApiVersion"),
        f"{source}.requires.registryApiVersion",
    )
    if expected_api is not None and required_api is not None and required_api != expected_api:
        fail(f"{source}.requires.registryApiVersion {required_api} does not match manifest {expected_api}")


def read_tar_member(tar_path, candidates):
    with tarfile.open(tar_path, "r:*") as tar:
        names = {member.name.lstrip("./"): member for member in tar.getmembers()}
        for candidate in candidates:
            member = names.get(candidate.lstrip("./"))
            if member is not None:
                extracted = tar.extractfile(member)
                if extracted is None:
                    fail(f"{os.path.basename(tar_path)}:{candidate} is not a regular file")
                    return ""
                return extracted.read().decode("utf-8", errors="replace")
    fail(f"{os.path.basename(tar_path)} is missing one of: {', '.join(candidates)}")
    return ""


def has_unescaped_expansion(line):
    for token in ("$!", "$child"):
        index = line.find(token)
        while index >= 0:
            slash_count = 0
            cursor = index - 1
            while cursor >= 0 and line[cursor] == "\\":
                slash_count += 1
                cursor -= 1
            if slash_count % 2 == 0:
                return True
            index = line.find(token, index + 1)
    return False


def check_unquoted_heredocs(script_text):
    lines = script_text.splitlines()
    index = 0
    heredoc_re = re.compile(r"<<-?\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\1")
    while index < len(lines):
        line = lines[index]
        match = heredoc_re.search(line)
        if not match:
            index += 1
            continue
        quote, delimiter = match.group(1), match.group(2)
        start_line = index + 1
        index += 1
        while index < len(lines) and lines[index].strip() != delimiter:
            if quote == "" and has_unescaped_expansion(lines[index]):
                fail(
                    "pi-web register-service.sh has unescaped $!/$child inside an unquoted heredoc "
                    f"starting at line {start_line}; this can truncate pi-web.json during registration"
                )
                break
            index += 1
        index += 1


def validate_pi_agent_payload(pi_agent_entry):
    archive_path = os.path.join(payload_dir, pi_agent_entry["archive"])
    with tarfile.open(archive_path, "r:*") as tar:
        members = {member.name.lstrip("./"): member for member in tar.getmembers()}
        sentinel = members.get("bin/openhouse-pi-agent-sentinel")
        if sentinel is None or sentinel.size <= 0:
            fail("pi-agent.tar is missing non-empty bin/openhouse-pi-agent-sentinel")
        for member in members.values():
            name = member.name.lstrip("./")
            if member.isfile() and member.size == 0 and (
                name.endswith("/services.d/pi-agent.json")
                or name in ("services.d/pi-agent.json",)
            ):
                fail(f"pi-agent.tar contains a zero-byte generated spec candidate: {name}")

    register_script = read_tar_member(archive_path, ["scripts/register-service.sh"])
    if not register_script:
        return
    if '"provider": "termux-process"' not in register_script:
        fail("pi-agent register-service.sh must register provider termux-process")
    if '"strategy": "termux-process"' not in register_script:
        fail("pi-agent register-service.sh must declare runtime strategy termux-process")
    if "child=\\$!" not in register_script:
        fail("pi-agent register-service.sh must track a stable shell supervisor child pid")
    if re.search(r">\s*\"\$SPEC_PATH\"", register_script) or re.search(r">\s*'\$SPEC_PATH'", register_script):
        fail("pi-agent register-service.sh writes directly to $SPEC_PATH; write a temp file, validate JSON, then mv atomically")


def validate_pi_web_payload(pi_web_entry):
    archive_path = os.path.join(payload_dir, pi_web_entry["archive"])
    with tarfile.open(archive_path, "r:*") as tar:
        members = {member.name.lstrip("./"): member for member in tar.getmembers()}
        start = members.get("bin/openhouse-pi-web-start")
        if start is None or start.size <= 0:
            fail("pi-web.tar is missing non-empty bin/openhouse-pi-web-start")
        launcher = members.get("bin/pi-web")
        if launcher is None or launcher.size <= 0:
            fail("pi-web.tar is missing non-empty bin/pi-web global command launcher")
        for member in members.values():
            name = member.name.lstrip("./")
            if member.isfile() and member.size == 0 and (
                name.endswith("/services.d/pi-web.json")
                or name.endswith("/components.d/pi-web.json")
                or name in ("services.d/pi-web.json", "components.d/pi-web.json")
            ):
                fail(f"pi-web.tar contains a zero-byte generated spec candidate: {name}")

    register_script = read_tar_member(archive_path, ["scripts/register-service.sh"])
    if not register_script:
        return
    check_unquoted_heredocs(register_script)
    if re.search(r">\s*\"\$SPEC_PATH\"", register_script) or re.search(r">\s*'\$SPEC_PATH'", register_script):
        fail("pi-web register-service.sh writes directly to $SPEC_PATH; write a temp file, validate JSON, then mv atomically")
    if re.search(r">\s*\"\$COMPONENT_PATH\"", register_script) or re.search(r">\s*'\$COMPONENT_PATH'", register_script):
        fail("pi-web register-service.sh writes directly to $COMPONENT_PATH; write a temp file, validate JSON, then mv atomically")
    if "PI_WEB_HOST" not in register_script or "HOSTNAME" not in register_script:
        fail("pi-web register-service.sh must set PI_WEB_HOST and HOSTNAME for the service environment")
    if "pi-web --host" not in register_script:
        fail("pi-web register-service.sh must launch the global pi-web command")


def validate_service_manager_payload(entry):
    archive_path = os.path.join(payload_dir, entry["archive"])
    expected_binary_sha = str(entry.get("binarySha256") or "").lower()
    with tarfile.open(archive_path, "r:*") as tar:
        members = {member.name.lstrip("./"): member for member in tar.getmembers()}
        binary = members.get("service-manager")
        if binary is None or not binary.isfile() or binary.size <= 0:
            fail("service-manager.tar is missing non-empty service-manager binary")
        else:
            if binary.mode & 0o111 == 0:
                fail("service-manager.tar service-manager binary must be executable")
            extracted = tar.extractfile(binary)
            binary_bytes = extracted.read() if extracted is not None else b""
            if not re.fullmatch(r"[0-9a-f]{64}", expected_binary_sha):
                fail("service-manager binarySha256 must be a lowercase 64-char hex digest")
            else:
                actual_binary_sha = hashlib.sha256(binary_bytes).hexdigest()
                if actual_binary_sha != expected_binary_sha:
                    fail(
                        "service-manager binarySha256 mismatch: "
                        f"expected {expected_binary_sha}, actual {actual_binary_sha}"
                    )
            if entry.get("platform") == "termux-arm64":
                is_arm64_elf = (
                    len(binary_bytes) >= 20
                    and binary_bytes[:4] == b"\x7fELF"
                    and binary_bytes[4] == 2
                    and binary_bytes[5] == 1
                    and int.from_bytes(binary_bytes[18:20], "little") == 183
                )
                if not is_arm64_elf:
                    fail("service-manager binary must be a little-endian ELF64 AArch64 executable")
                elif b"/system/bin/linker64\x00" not in binary_bytes:
                    fail("service-manager termux-arm64 binary must use /system/bin/linker64")

        for required_script in ("scripts/install.sh", "scripts/check.sh"):
            member = members.get(required_script)
            if member is None or not member.isfile() or member.size <= 0:
                fail(f"service-manager.tar is missing non-empty {required_script}")
            elif member.mode & 0o111 == 0:
                fail(f"service-manager.tar {required_script} must be executable")


def validate_openhouse_web_payload(entry):
    archive_path = os.path.join(payload_dir, entry["archive"])
    with tarfile.open(archive_path, "r:*") as tar:
        members = {member.name.lstrip("./"): member for member in tar.getmembers()}
        for required_file in (
            "README.md",
            "package.json",
            "src/server.mjs",
            "src/auth.mjs",
            "src/password-store.mjs",
            "public/index.html",
            "config/openhouse-web.service.json",
            "config/openhouse.component.json",
            "scripts/build.mjs",
            "scripts/check.mjs",
            "scripts/install.sh",
            "scripts/check.sh",
            "scripts/register-service.sh",
        ):
            member = members.get(required_file)
            if member is None or member.size <= 0:
                fail(f"openhouse-web.tar is missing non-empty {required_file}")
        for required_script in (
            "scripts/install.sh",
            "scripts/check.sh",
            "scripts/register-service.sh",
        ):
            member = members.get(required_script)
            if member is not None and member.mode & 0o111 == 0:
                fail(f"openhouse-web.tar {required_script} must be executable")
        package_member = members.get("package.json")
        if package_member is not None:
            extracted = tar.extractfile(package_member)
            if extracted is not None:
                package_doc = json.loads(extracted.read().decode("utf-8"))
                if package_doc.get("version") != entry.get("version"):
                    fail(
                        "openhouse-web package version mismatch: "
                        f"manifest={entry.get('version')}, package={package_doc.get('version')}"
                    )
        password_member = members.get("src/password-store.mjs")
        if password_member is not None:
            extracted = tar.extractfile(password_member)
            password_source = extracted.read().decode("utf-8") if extracted is not None else ""
            for fragment in (
                "DEFAULT_PASSWORD = '123456'",
                "MIN_PASSWORD_LENGTH = 6",
                "MAX_PASSWORD_LENGTH = 128",
                "0o700",
                "0o600",
            ):
                if fragment not in password_source:
                    fail(f"openhouse-web password store missing contract fragment: {fragment}")
        server_member = members.get("src/server.mjs")
        if server_member is not None:
            extracted = tar.extractfile(server_member)
            server_source = extracted.read().decode("utf-8") if extracted is not None else ""
            for fragment in (
                "/api/v1/session/password",
                "/api/v1/password",
                "auth.revokeSessions()",
                "auth.issueSession()",
            ):
                if fragment not in server_source:
                    fail(f"openhouse-web server missing password auth contract fragment: {fragment}")
        service_member = members.get("config/openhouse-web.service.json")
        if service_member is not None:
            extracted = tar.extractfile(service_member)
            if extracted is not None:
                service_doc = json.loads(extracted.read().decode("utf-8"))
                service = service_doc.get("service") or {}
                if service.get("residentByDefault") is not True:
                    fail("openhouse-web service must declare residentByDefault=true")
                ports = service.get("ports") or []
                if not ports or ports[0].get("preferred") != 22110:
                    fail("openhouse-web service must prefer fixed port 22110")


def validate_wuyou_payload(entry):
    archive_path = os.path.join(payload_dir, entry["archive"])
    expected_binary_sha = str(entry.get("binarySha256") or "").lower()
    expected_binary_size = entry.get("binarySize")
    with tarfile.open(archive_path, "r:*") as tar:
        members = {member.name.lstrip("./"): member for member in tar.getmembers()}
        binary = members.get("wuyou")
        if binary is None or binary.size <= 0:
            fail("wuyou.tar is missing non-empty wuyou binary")
        elif isinstance(expected_binary_size, int) and binary.size != expected_binary_size:
            fail(f"wuyou binarySize mismatch: expected {expected_binary_size}, actual {binary.size}")
        for required_script in ("scripts/install.sh", "scripts/check.sh"):
            member = members.get(required_script)
            if member is None or member.size <= 0:
                fail(f"wuyou.tar is missing non-empty {required_script}")
        if binary is not None and re.fullmatch(r"[0-9a-f]{64}", expected_binary_sha):
            extracted = tar.extractfile(binary)
            if extracted is not None:
                actual = hashlib.sha256(extracted.read()).hexdigest()
                if actual != expected_binary_sha:
                    fail(f"wuyou binarySha256 mismatch: expected {expected_binary_sha}, actual {actual}")


manifest = load_json(manifest_path)
payload_manifest = load_json(payload_manifest_path)

components = by_id(component_array(manifest, "components"), "manifest.json")
payloads = by_id(component_array(payload_manifest, "payloads"), "payload-manifest.json")

required = ("service-manager", "openhouse-web", "pi-agent", "pi-web", "wuyou", "aionui-web")
registry_managed = ("openhouse-web", "pi-agent", "pi-web", "aionui-web")
for component_id in required:
    if component_id not in components:
        fail(f"manifest.json missing required component {component_id}")
    if component_id not in payloads:
        fail(f"payload-manifest.json missing required payload {component_id}")

manifest_registry_api_version = manifest_registry_api(manifest, "manifest.json")
payload_registry_api_version = manifest_registry_api(payload_manifest, "payload-manifest.json")
if (
    manifest_registry_api_version is not None
    and payload_registry_api_version is not None
    and manifest_registry_api_version != payload_registry_api_version
):
    fail(
        "registryApiVersion mismatch between manifest.json and payload-manifest.json: "
        f"{manifest_registry_api_version} != {payload_registry_api_version}"
    )

expected_registry_api_version = manifest_registry_api_version or payload_registry_api_version
component_service_manager_api = service_manager_registry_api(
    components.get("service-manager"),
    "manifest.json",
    expected_registry_api_version,
)
payload_service_manager_api = service_manager_registry_api(
    payloads.get("service-manager"),
    "payload-manifest.json",
    expected_registry_api_version,
)
for observed, source in (
    (component_service_manager_api, "manifest.json"),
    (payload_service_manager_api, "payload-manifest.json"),
):
    if expected_registry_api_version is not None and observed is not None and observed != expected_registry_api_version:
        fail(f"{source}:service-manager provides registryApiVersion {observed}, expected {expected_registry_api_version}")

for component_id in registry_managed:
    component_registry_requirement(
        components.get(component_id),
        f"manifest.json:{component_id}",
        expected_registry_api_version,
    )
    component_registry_requirement(
        payloads.get(component_id),
        f"payload-manifest.json:{component_id}",
        expected_registry_api_version,
    )

for component_id, entry in components.items():
    validate_archive(entry, "manifest.json")
for component_id, entry in payloads.items():
    validate_archive(entry, "payload-manifest.json")
for component_id in sorted(set(components) & set(payloads)):
    compare_entries(components[component_id], payloads[component_id], component_id)

if "pi-agent" in components and isinstance(components["pi-agent"].get("archive"), str):
    validate_pi_agent_payload(components["pi-agent"])
if "pi-web" in components and isinstance(components["pi-web"].get("archive"), str):
    validate_pi_web_payload(components["pi-web"])
if "service-manager" in components and isinstance(components["service-manager"].get("archive"), str):
    validate_service_manager_payload(components["service-manager"])
if "openhouse-web" in components and isinstance(components["openhouse-web"].get("archive"), str):
    validate_openhouse_web_payload(components["openhouse-web"])
if "wuyou" in components and isinstance(components["wuyou"].get("archive"), str):
    validate_wuyou_payload(components["wuyou"])

for component_id in required:
    entry = components.get(component_id) or {}
    version = entry.get("version") or entry.get("sourceCommit") or entry.get("sha256", "")[:12] or "unknown"
    archive = entry.get("archive", "unknown")
    print(f"payload-ok-candidate {component_id} version={version} archive={archive}")

for message in warnings:
    print(f"WARNING: {message}", file=sys.stderr)

if errors:
    print("OpenHouse payload validation failed:", file=sys.stderr)
    for message in errors:
        print(f"  - {message}", file=sys.stderr)
    sys.exit(1)

print(f"OpenHouse payload validation passed: {len(digest_cache)} archives checked in {payload_dir}")
PY
