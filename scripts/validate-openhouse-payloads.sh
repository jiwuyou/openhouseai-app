#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PAYLOAD_DIR="${1:-$REPO_DIR/app/src/main/assets/openhouse/product-payloads}"

if ! command -v python3 >/dev/null 2>&1; then
  printf 'validate-openhouse-payloads: python3 is required for JSON/tar validation.\n' >&2
  exit 2
fi

python3 - \
  "$PAYLOAD_DIR" \
  "$REPO_DIR/app/src/main/assets/openhouse/pi-prompts" \
  "${PI_WEB_REQUIRED_BRANCH:-openhouse}" \
  "${PI_WEB_REQUIRED_COMMIT:-19a4496149bf8198be1362e31d81d79b5d250051}" \
  "${PI_RUST_REQUIRED_COMMIT:-ad719ad3d42173be9293a020492b7d10f85c95fe}" \
  "$REPO_DIR/app/src/main/assets/smallphoneai/bootstrap/subjects.d/service-control.json" \
  "$REPO_DIR/app/src/main/assets/smallphoneai/bootstrap/openhouseai-manifest.json" \
  "$REPO_DIR/app/src/main/assets/smallphoneai/bootstrap/subjects.d/pi-agent.json" \
  "$REPO_DIR/app/src/main/assets/smallphoneai/bootstrap" \
  "$REPO_DIR/app/src/main/assets/smallphoneai/bootstrap/scripts/50-install-runtime-components.sh" \
  "$REPO_DIR/app/src/main/assets/smallphoneai/bootstrap/scripts/60-start-smallphone.sh" \
  "$REPO_DIR/app/build.gradle" \
  "$REPO_DIR/app/src/main/assets/maintainer/update-termux-packages.sh" \
  "$REPO_DIR/app/src/main/assets/smallphoneai/bootstrap/scripts/12-update-termux-packages.sh" <<'PY'
import hashlib
import json
import os
import re
import sys
import tarfile

payload_dir = os.path.abspath(sys.argv[1])
prompt_assets_dir = os.path.abspath(sys.argv[2])
required_pi_web_branch = sys.argv[3]
required_pi_web_commit = sys.argv[4].lower()
required_pi_rust_commit = sys.argv[5].lower()
service_control_path = os.path.abspath(sys.argv[6])
bootstrap_manifest_path = os.path.abspath(sys.argv[7])
pi_agent_subject_path = os.path.abspath(sys.argv[8])
bootstrap_root = os.path.abspath(sys.argv[9])
component_installer_path = os.path.abspath(sys.argv[10])
service_starter_path = os.path.abspath(sys.argv[11])
app_build_gradle_path = os.path.abspath(sys.argv[12])
termux_package_delegate_path = os.path.abspath(sys.argv[13])
termux_package_bootstrap_path = os.path.abspath(sys.argv[14])
manifest_path = os.path.join(payload_dir, "manifest.json")
payload_manifest_path = os.path.join(payload_dir, "payload-manifest.json")
native_runtime_asset_path = os.path.join(
    os.path.abspath(os.path.join(payload_dir, "../../../../../..")),
    "native-app", "src", "main", "assets", "openhouse-runtime", "runtime-aarch64.tgz",
)

errors = []
warnings = []
digest_cache = {}

if os.path.exists(os.path.join(payload_dir, "pi-agent.tar")):
    errors.append("legacy Node pi-agent.tar must be removed; pi-runtime.tar is the only Pi payload")

if not re.fullmatch(r"[0-9a-f]{40}", required_pi_web_commit):
    raise SystemExit("PI_WEB_REQUIRED_COMMIT must be a full 40-character lowercase Git commit")
if not re.fullmatch(r"[0-9a-f]{40}", required_pi_rust_commit):
    raise SystemExit("PI_RUST_REQUIRED_COMMIT must be a full 40-character lowercase Git commit")


def fail(message):
    errors.append(message)


def warn(message):
    warnings.append(message)


def validate_service_control_contract():
    expected = [
        {"id": "openhouse-web", "runtime": "termux", "manager": "service-manager"},
        {"id": "pi-agent", "runtime": "termux", "manager": "service-manager"},
        {"id": "pi-web", "runtime": "termux", "manager": "service-manager"},
        {"id": "aionui-web", "runtime": "termux", "manager": "service-manager"},
    ]
    try:
        with open(service_control_path, "r", encoding="utf-8") as handle:
            subject = json.load(handle)
    except Exception as exc:
        fail(f"invalid service-control subject: {exc}")
        return
    if subject.get("serviceRefs") != expected:
        fail("service-control subject must reference exactly openhouse-web, pi-agent, pi-web, and aionui-web in Termux service-manager")
    try:
        with open(component_installer_path, "r", encoding="utf-8") as handle:
            installer = handle.read()
    except Exception as exc:
        fail(f"cannot read component installer service-control template: {exc}")
        return
    marker = '"serviceRefs":' + json.dumps(expected, separators=(",", ":"))
    if marker not in installer:
        fail("embedded service-control template does not contain the exact required serviceRefs")


def validate_release_upgrade_contract():
    try:
        with open(app_build_gradle_path, "r", encoding="utf-8") as handle:
            build_gradle = handle.read()
    except Exception as exc:
        fail(f"cannot read app release version contract: {exc}")
        return
    code_match = re.search(r"^\s*versionCode\s+(\d+)\s*$", build_gradle, re.MULTILINE)
    name_match = re.search(r'^\s*versionName\s+"([^"]+)"\s*$', build_gradle, re.MULTILINE)
    if not code_match or int(code_match.group(1)) < 125:
        fail("All-in-One versionCode must be at least 125 so the repaired APK stages fresh assets")
    if not name_match or name_match.group(1) == "0.118.106":
        fail("All-in-One versionName must not remain 0.118.106 after the first-install repair")
    elif name_match.group(1) != "0.118.107":
        fail(f"All-in-One repair release versionName must be 0.118.107, got {name_match.group(1)!r}")


def validate_pi_dynamic_registration_contract():
    sources = {}
    for label, path in (
        ("50-install-runtime-components.sh", component_installer_path),
        ("60-start-smallphone.sh", service_starter_path),
    ):
        try:
            with open(path, "r", encoding="utf-8") as handle:
                sources[label] = handle.read()
        except Exception as exc:
            fail(f"cannot read Pi dynamic registration source {label}: {exc}")
            sources[label] = ""

    for label, source in sources.items():
        for required in (
            "dynamic_register_pi_service",
            "/api/v1/registry/apply",
            "/api/v1/services/$service_id/register",
            '[ "$service_id" = "pi-agent" ]',
            '[ "$service_id" = "pi-web" ]',
        ):
            if required not in source:
                fail(f"{label} is missing stable Pi dynamic registration contract: {required}")

    installer = sources["50-install-runtime-components.sh"]
    for required in (
        "*:pi-agent:scripts/register-service.sh|*:pi-web:scripts/register-service.sh",
        "run_with_service_manager_auth",
        "SERVICE_MANAGER_URL=",
        '&& dynamic_register_pi_service "$payload_name" "$sm_token"',
        'run_repo_script "$name" "$dir" "scripts/register-service.sh" "1" "$payload_name"',
    ):
        if required not in installer:
            fail(f"50-install-runtime-components.sh is missing Pi dynamic registration orchestration: {required}")

    starter = sources["60-start-smallphone.sh"]
    for component_id in ("pi-agent", "pi-web"):
        marker = f'run_register_if_present "{component_id}"'
        if marker not in starter:
            fail(f"60-start-smallphone.sh must dynamically register stable service {component_id}: {marker}")
    for required in (
        "run_with_service_manager_auth",
        'SERVICE_MANAGER_URL="$sm_url"',
        'dynamic_register_pi_service "$name"',
        'run_register_if_present "pi-agent" "$pi_agent_dir" || exit 1',
        'run_register_if_present "pi-web" "$pi_web_dir" || exit 1',
    ):
        if required not in starter:
            fail(f"60-start-smallphone.sh is missing authenticated dynamic registration orchestration: {required}")


def validate_termux_package_contract():
    try:
        with open(termux_package_delegate_path, "r", encoding="utf-8") as handle:
            delegate = handle.read()
    except Exception as exc:
        fail(f"cannot read Termux package delegate: {exc}")
        return

    for fragment in (
        "SMALLPHONEAI_BOOTSTRAP",
        "OPENHOUSEAI_MAINTAINER_DIR",
        "SMALLPHONEAI_MAINTAINER_DIR",
        "/../bootstrap/bootstrap.sh",
        'exec bash "$bootstrap" termux-packages',
    ):
        if fragment not in delegate:
            fail(f"maintainer Termux package delegate is missing contract fragment: {fragment}")

    forbidden_delegate_patterns = {
        r"\bapt\b": "apt implementation",
        r"\bpkg\b": "pkg implementation",
        r"\bproot-distro\b": "package list",
        r"\bopenssh\b": "package list",
        r"\bcurl\b": "package list",
        r"\bjq\b": "package list",
        r"\bca-certificates\b": "package list",
        r"mirrors\.": "mirror policy",
        r"termux_main_repo": "mirror policy",
    }
    for pattern, label in forbidden_delegate_patterns.items():
        if re.search(pattern, delegate):
            fail(f"maintainer Termux package delegate must not contain {label}; bootstrap is the single implementation")

    try:
        with open(termux_package_bootstrap_path, "r", encoding="utf-8") as handle:
            bootstrap = handle.read()
    except Exception as exc:
        fail(f"cannot read canonical Termux package bootstrap: {exc}")
        return

    jq_install_positions = [
        match.start()
        for match in re.finditer(r"^\s*run_termux_apt_install\b[^\n]*\bjq\b", bootstrap, re.MULTILINE)
    ]
    if not jq_install_positions:
        fail("canonical Termux package bootstrap must install jq")
        return

    final_jq_check = bootstrap.rfind("if ! jq --version")
    if final_jq_check <= jq_install_positions[-1]:
        fail("canonical Termux package bootstrap must perform a final jq --version check after jq installation")
        return
    final_jq_block = bootstrap[final_jq_check:]
    if "exit 1" not in final_jq_block:
        fail("canonical Termux package bootstrap final jq check must fail the stage when jq is unavailable")


def validate_bootstrap_pi_contract(product_manifest):
    bootstrap_manifest = load_json(bootstrap_manifest_path)
    product_components = by_id(
        component_array(product_manifest, "components"),
        "manifest.json",
    )
    pi_product = product_components.get("pi-agent")
    if not isinstance(pi_product, dict):
        fail("manifest.json must preserve the stable pi-agent component id")
        return

    runtime_payloads = bootstrap_manifest.get("runtimePayloads")
    if not isinstance(runtime_payloads, dict):
        fail("bootstrap manifest runtimePayloads must be an object")
        return
    bootstrap_payloads = by_id(
        component_array(runtime_payloads, "payloads"),
        "bootstrap runtimePayloads.payloads",
    )
    pi_bootstrap = bootstrap_payloads.get("pi-agent")
    if not isinstance(pi_bootstrap, dict):
        fail("bootstrap manifest must preserve the stable pi-agent payload id")
        return

    archive = os.path.basename(str(pi_bootstrap.get("apkAssetPath") or ""))
    target_dir = os.path.basename(str(pi_bootstrap.get("targetDir") or "").rstrip("/"))
    expected_archive = str(pi_product.get("archive") or "")
    expected_target = str(pi_product.get("targetDir") or "")
    if archive != expected_archive:
        fail(
            "bootstrap/product pi-agent archive mismatch: "
            f"bootstrap={archive!r}, product={expected_archive!r}"
        )
    if target_dir != expected_target:
        fail(
            "bootstrap/product pi-agent targetDir mismatch: "
            f"bootstrap={target_dir!r}, product={expected_target!r}"
        )
    if archive != "pi-runtime.tar" or target_dir != "pi-runtime":
        fail("stable pi-agent id must map exactly to pi-runtime.tar and targetDir pi-runtime")
    if archive and not os.path.isfile(os.path.join(payload_dir, archive)):
        fail(f"bootstrap pi-agent archive does not exist in product payloads: {archive}")

    subject = load_json(pi_agent_subject_path)
    if subject.get("id") != "pi-agent":
        fail("Pi subject must preserve id pi-agent")
    service_refs = subject.get("serviceRefs")
    if not isinstance(service_refs, list):
        fail("Pi subject serviceRefs must be an array")
        service_refs = []
    pi_service = next(
        (entry for entry in service_refs if isinstance(entry, dict) and entry.get("id") == "pi-agent"),
        None,
    )
    if not isinstance(pi_service, dict):
        fail("Pi subject must preserve service id pi-agent")
    else:
        if pi_service.get("command") != "openhouse-pi-runtime-start":
            fail("Pi subject pi-agent service must launch openhouse-pi-runtime-start")
        if pi_service.get("workingDirectory") != "$HOME/workspace":
            fail("Pi subject pi-agent service workingDirectory must be $HOME/workspace")
    subject_text = json.dumps(subject, ensure_ascii=False, separators=(",", ":"))
    for required in (
        "127.0.0.1:8765",
        "/.local/share/openhouseai/runtime/state/token",
        "/smallphoneai-repos/pi-runtime",
    ):
        if required not in subject_text:
            fail(f"Pi subject is missing Rust runtime contract: {required}")

    forbidden_literals = (
        "pi-agent.tar",
        "smallphoneai-repos/pi-agent",
        "openhouse-pi-agent-sentinel",
    )
    source_paths = [bootstrap_manifest_path, pi_agent_subject_path]
    for root, _, files in os.walk(bootstrap_root):
        for name in files:
            if name == "bootstrap.sh" or name.endswith(".sh"):
                source_paths.append(os.path.join(root, name))
    for source_path in sorted(set(source_paths)):
        try:
            with open(source_path, "r", encoding="utf-8") as handle:
                source = handle.read()
        except Exception as exc:
            fail(f"cannot read bootstrap Pi contract source {source_path}: {exc}")
            continue
        relative = os.path.relpath(source_path, bootstrap_root)
        for forbidden in forbidden_literals:
            if forbidden in source:
                fail(f"bootstrap source {relative} contains legacy Node Pi fragment: {forbidden}")
        legacy_node_pi_patterns = (
            r"\bnpm\s+(?:install|i|exec|run)\b[^\n]{0,200}(?:pi-agent|pi-coding-agent)",
            r"(?:@[^\s'\"]+/)?pi-coding-agent",
            r"\.npm-global/bin[^\n]{0,200}command\s+-v\s+pi\b",
        )
        for pattern in legacy_node_pi_patterns:
            if re.search(pattern, source, re.IGNORECASE):
                fail(f"bootstrap source {relative} contains legacy Node/npm Pi install or status logic")
                break


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
        "gatewaySha256",
        "gatewaySize",
        "version",
        "platform",
        "registryApiVersion",
        "requires",
        "provides",
        "sourceRepo",
        "sourceBranch",
        "sourceCommit",
        "sourceTreeSha256",
    ):
        if field in ("sourceRepo", "sourceBranch", "sourceCommit", "sourceTreeSha256"):
            disagrees = left.get(field) != right.get(field)
        else:
            disagrees = field in left and field in right and left.get(field) != right.get(field)
        if disagrees:
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
    require_true(provides.get("residency"), f"{source}:service-manager.provides.residency")
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
    requirement_key = "registrationRequires" if "registrationRequires" in entry else "requires"
    requires = entry.get(requirement_key)
    if not isinstance(requires, dict):
        fail(f"{source}.{requirement_key} must be an object")
        return
    service_manager_range = str(requires.get("serviceManager") or "").strip()
    if not service_manager_range:
        fail(f"{source}.{requirement_key}.serviceManager must declare the supported service-manager range")
    required_api = require_positive_int(
        requires.get("registryApiVersion"),
        f"{source}.{requirement_key}.registryApiVersion",
    )
    if expected_api is not None and required_api is not None and required_api != expected_api:
        fail(f"{source}.{requirement_key}.registryApiVersion {required_api} does not match manifest {expected_api}")


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
    if os.path.basename(archive_path) != "pi-runtime.tar":
        fail("pi-agent must reference pi-runtime.tar, not the legacy Node payload")
    if pi_agent_entry.get("sourceCommit") != required_pi_rust_commit:
        fail(f"pi-agent sourceCommit must be pinned to {required_pi_rust_commit}")
    expected_pi_sha = str(pi_agent_entry.get("binarySha256") or "").lower()
    expected_gateway_sha = str(pi_agent_entry.get("gatewaySha256") or "").lower()
    with tarfile.open(archive_path, "r:*") as tar:
        members = {member.name.lstrip("./"): member for member in tar.getmembers()}
        for member_name, expected_sha, label in (
            ("bin/pi", expected_pi_sha, "Pi Rust"),
            ("bin/openhouse-pi-runtime", expected_gateway_sha, "OpenHouse Pi runtime"),
        ):
            member = members.get(member_name)
            if member is None or not member.isfile() or member.size <= 0:
                fail(f"pi-runtime.tar is missing non-empty {member_name}")
                continue
            extracted = tar.extractfile(member)
            data = extracted.read() if extracted is not None else b""
            actual_sha = hashlib.sha256(data).hexdigest()
            if not re.fullmatch(r"[0-9a-f]{64}", expected_sha):
                fail(f"pi-agent {label} checksum metadata is invalid")
            elif actual_sha != expected_sha:
                fail(f"pi-agent {label} checksum mismatch: expected {expected_sha}, actual {actual_sha}")
            is_arm64_elf = (
                len(data) >= 20 and data[:4] == b"\x7fELF" and data[4] == 2
                and data[5] == 1 and int.from_bytes(data[18:20], "little") == 183
            )
            if not is_arm64_elf:
                fail(f"pi-agent {label} must be a little-endian ELF64 AArch64 executable")
        extension_members = [
            member for name, member in members.items()
            if name.startswith("extensions/") and member.isfile() and member.size > 0
            and name.rsplit(".", 1)[-1] in ("js", "mjs", "ts")
        ]
        if not extension_members:
            fail("pi-runtime.tar must contain at least one OpenHouse Pi extension")
        for required in (
            "extensions/openhouse-tools/extension.json",
            "extensions/openhouse-tools/index.ts",
            "extensions/openhouse-tools/android-bridge-request.sh",
        ):
            member = members.get(required)
            if member is None or not member.isfile() or member.size <= 0:
                fail(f"pi-runtime.tar is missing non-empty {required}")
        helper = members.get("extensions/openhouse-tools/android-bridge-request.sh")
        if helper is not None and helper.mode & 0o111 == 0:
            fail("android-bridge-request.sh must retain executable permissions")
        for required in ("scripts/install.sh", "scripts/check.sh", "scripts/register-service.sh", "metadata/build.json"):
            member = members.get(required)
            if member is None or not member.isfile() or member.size <= 0:
                fail(f"pi-runtime.tar is missing non-empty {required}")
        for member in members.values():
            name = member.name.lstrip("./")
            if member.isfile() and member.size == 0 and (
                name.endswith("/services.d/pi-agent.json")
                or name in ("services.d/pi-agent.json",)
            ):
                fail(f"pi-runtime.tar contains a zero-byte generated spec candidate: {name}")

    register_script = read_tar_member(archive_path, ["scripts/register-service.sh"])
    install_script = read_tar_member(archive_path, ["scripts/install.sh"])
    check_script = read_tar_member(archive_path, ["scripts/check.sh"])
    for forbidden in ("npm install", "termuxNode", "Node >=", "openhouse-pi-agent-sentinel"):
        if forbidden in register_script + install_script + check_script:
            fail(f"Pi Rust payload contains legacy Node runtime fragment: {forbidden}")
    if not register_script:
        return
    if '"provider": "termux-process"' not in register_script:
        fail("pi-agent register-service.sh must register provider termux-process")
    if '"strategy": "termux-process"' not in register_script:
        fail("pi-agent register-service.sh must declare runtime strategy termux-process")
    if "child=\\$!" not in register_script:
        fail("pi-agent register-service.sh must track a stable shell supervisor child pid")
    if "openhouse-pi-runtime-start" not in register_script:
        fail("pi-agent register-service.sh must launch openhouse-pi-runtime-start")
    if "mv \"$tmp\" \"$spec\"" not in register_script:
        fail("pi-agent register-service.sh must validate and atomically move the generated service spec")


def validate_native_install_bundle(manifest, payload_manifest):
    left = manifest.get("nativeInstallBundle")
    right = payload_manifest.get("nativeInstallBundle")
    if not isinstance(left, dict) or not isinstance(right, dict):
        fail("both manifests must declare nativeInstallBundle")
        return
    if left != right:
        fail("nativeInstallBundle differs between manifest.json and payload-manifest.json")
        return
    if left.get("applicationId") != "com.wuxianpi":
        fail("nativeInstallBundle.applicationId must be com.wuxianpi")
    archive = left.get("archive")
    if archive != "wuxianpi-native-install.tar":
        fail("nativeInstallBundle.archive must be wuxianpi-native-install.tar")
        return
    path = os.path.join(payload_dir, archive)
    if not os.path.isfile(path):
        fail(f"native install bundle is missing: {archive}")
        return
    actual_size, actual_sha = file_digest(path)
    if left.get("size") != actual_size or left.get("sha256") != actual_sha:
        fail("native install bundle checksum or size mismatch")
    with tarfile.open(path, "r:*") as tar:
        members = {member.name.lstrip("./"): member for member in tar.getmembers()}
        for required in ("install.sh", "payload/pi-runtime.tar", "payload/pi-runtime.tar.sha256"):
            member = members.get(required)
            if member is None or not member.isfile() or member.size <= 0:
                fail(f"native install bundle is missing non-empty {required}")


def validate_native_runtime_asset(manifest, payload_manifest):
    left = manifest.get("nativeRuntimeAsset")
    right = payload_manifest.get("nativeRuntimeAsset")
    if not isinstance(left, dict) or not isinstance(right, dict):
        fail("both manifests must declare nativeRuntimeAsset")
        return
    if left != right:
        fail("nativeRuntimeAsset differs between manifest.json and payload-manifest.json")
        return
    if left.get("abi") != "arm64-v8a" or left.get("applicationId") != "com.wuxianpi":
        fail("nativeRuntimeAsset must target arm64-v8a and com.wuxianpi")
    if not os.path.isfile(native_runtime_asset_path):
        fail(f"Native APK runtime asset is missing: {native_runtime_asset_path}")
        return
    actual_size, actual_sha = file_digest(native_runtime_asset_path)
    if left.get("size") != actual_size or left.get("sha256") != actual_sha:
        fail("nativeRuntimeAsset checksum or size mismatch")
    with tarfile.open(native_runtime_asset_path, "r:gz") as tar:
        members = {member.name.lstrip("./"): member for member in tar.getmembers()}
        for required in (
            "install.sh", "bin/pi", "bin/openhouse-pi-runtime", "scripts/install.sh",
            "scripts/register-service.sh", "metadata/build.json",
            "extensions/openhouse-tools/extension.json", "extensions/openhouse-tools/index.ts",
            "extensions/openhouse-tools/android-bridge-request.sh",
        ):
            member = members.get(required)
            if member is None or not member.isfile() or member.size <= 0:
                fail(f"Native APK runtime asset is missing non-empty {required}")
        for executable in ("install.sh", "bin/pi", "bin/openhouse-pi-runtime", "extensions/openhouse-tools/android-bridge-request.sh"):
            member = members.get(executable)
            if member is not None and member.mode & 0o111 == 0:
                fail(f"Native APK runtime asset executable bit is missing: {executable}")


def validate_pi_web_payload(pi_web_entry):
    archive_path = os.path.join(payload_dir, pi_web_entry["archive"])
    prompt_names = (
        "openhouse-first-config",
        "openhouse-docs",
        "openhouse-second-ai-handoff",
    )
    prompt_bytes = {}
    prompt_texts = {}
    with tarfile.open(archive_path, "r:*") as tar:
        members = {member.name.lstrip("./"): member for member in tar.getmembers()}
        start = members.get("bin/openhouse-pi-web-start")
        if start is None or start.size <= 0:
            fail("pi-web.tar is missing non-empty bin/openhouse-pi-web-start")
        launcher = members.get("bin/pi-web")
        if launcher is None or launcher.size <= 0:
            fail("pi-web.tar is missing non-empty bin/pi-web global command launcher")
        proxy_server = members.get("runtime/pi-web/openhouse-server.mjs")
        if proxy_server is None or proxy_server.size <= 0:
            fail("pi-web.tar is missing the OpenHouse WebSocket proxy server")
        for prompt_name in prompt_names:
            prompt_path = f"prompts/{prompt_name}.md"
            member = members.get(prompt_path)
            if member is None or not member.isfile() or member.size <= 0:
                fail(f"pi-web.tar is missing non-empty {prompt_path}")
                continue
            extracted = tar.extractfile(member)
            prompt_bytes[prompt_name] = extracted.read() if extracted is not None else b""
            prompt_texts[prompt_name] = prompt_bytes[prompt_name].decode("utf-8", errors="replace")
        for member in members.values():
            name = member.name.lstrip("./")
            if member.isfile() and member.size == 0 and (
                name.endswith("/services.d/pi-web.json")
                or name.endswith("/components.d/pi-web.json")
                or name in ("services.d/pi-web.json", "components.d/pi-web.json")
            ):
                fail(f"pi-web.tar contains a zero-byte generated spec candidate: {name}")

    source_branch = str(pi_web_entry.get("sourceBranch") or "")
    source_commit = str(pi_web_entry.get("sourceCommit") or "").lower()
    if not source_branch:
        fail("pi-web sourceBranch is missing")
    if not re.fullmatch(r"[0-9a-f]{40}", source_commit):
        fail("pi-web sourceCommit must be a full Git commit")
    source_tree_sha = str(pi_web_entry.get("sourceTreeSha256") or "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", source_tree_sha):
        fail("pi-web sourceTreeSha256 must identify the vendored web/pi-web source tree")
    if pi_web_entry.get("sourceRepo") != "https://github.com/jiwuyou/openhouseai-app.git":
        fail("pi-web sourceRepo must point to the OpenHouseAI monorepo")

    command_requirements = {
        "openhouse-first-config": (
            "# /openhouse-first-config",
            "$HOME/openhouse/docs",
            "/root/openhouse/docs",
            "$HOME/.local/share/openhouseai/handoffs/second-ai/latest",
            "agent identity",
            "HANDOFF.md",
            "system-check.json",
            "task.json",
            "不同",
        ),
        "openhouse-docs": (
            "# /openhouse-docs",
            "$HOME/openhouse/docs",
            "/root/openhouse/docs",
        ),
        "openhouse-second-ai-handoff": (
            "# /openhouse-second-ai-handoff",
            "$HOME/.local/share/openhouseai/handoffs/second-ai/latest",
            "agent identity",
            "HANDOFF.md",
            "system-check.json",
            "task.json",
            "不同",
        ),
    }
    for prompt_name, required_texts in command_requirements.items():
        prompt_text = prompt_texts.get(prompt_name, "")
        for required_text in required_texts:
            if required_text not in prompt_text:
                fail(f"pi-web prompt {prompt_name} is missing contract text: {required_text}")

    for prompt_name in prompt_names:
        source_path = os.path.join(prompt_assets_dir, f"{prompt_name}.md")
        if not os.path.isfile(source_path):
            fail(f"missing App-owned pi prompt asset: {source_path}")
            continue
        with open(source_path, "rb") as source_file:
            source_bytes = source_file.read()
        bundled_bytes = prompt_bytes.get(prompt_name, b"")
        if hashlib.sha256(bundled_bytes).digest() != hashlib.sha256(source_bytes).digest():
            fail(f"pi-web bundled prompt differs from App-owned source: {prompt_name}")

    first_config_prompt = prompt_texts.get("openhouse-first-config", "")
    for required_text in (
        "/openhouse-first-config",
        "$HOME/openhouse/docs",
        "/root/openhouse/docs",
        "$HOME/.local/share/openhouseai/handoffs/second-ai/latest",
    ):
        if required_text not in first_config_prompt:
            fail(f"openhouse-first-config prompt is missing required path or command: {required_text}")
    secret_patterns = (
        r"\bsk-[A-Za-z0-9_-]{12,}\b",
        r"(?i)\bBearer\s+[A-Za-z0-9._~+/-]{12,}",
        r"(?i)\b(api[_ -]?key|token|authorization|password)\s*[:=]\s*['\"]?[A-Za-z0-9._~+/-]{12,}",
    )
    for prompt_name, prompt_text in prompt_texts.items():
        for pattern in secret_patterns:
            if re.search(pattern, prompt_text):
                fail(f"pi-web prompt {prompt_name} contains secret-like material")

    install_script = read_tar_member(archive_path, ["scripts/install.sh"])
    if '$HOME/.pi/prompts' not in install_script and '$PI_AGENT_DIR/prompts' not in install_script:
        fail("pi-web install.sh must install prompts under $HOME/.pi/prompts by default")
    for prompt_name in prompt_names:
        if prompt_name not in install_script:
            fail(f"pi-web install.sh does not list prompt: {prompt_name}")
    if "install -m 600" not in install_script:
        fail("pi-web install.sh must install prompt files with private permissions")

    start_script = read_tar_member(archive_path, ["bin/openhouse-pi-web-start"])
    if "PI_WEB_DEFAULT_CWD" not in start_script:
        fail("pi-web launcher must expose the generic PI_WEB_DEFAULT_CWD")
    for required in ("openhouse-server.mjs", "OPENHOUSE_PI_RUNTIME_ORIGIN", "OPENHOUSE_PI_RUNTIME_TOKEN_FILE"):
        if required not in start_script:
            fail(f"pi-web launcher is missing Pi Rust transport contract: {required}")
    for legacy_name in (
        "OPENHOUSE_PI_WEB_DEFAULT_CWD",
        "OPENHOUSE_DOCS_DIR",
        "OPENHOUSE_SCRIPTS_DIR",
        "OPENHOUSE_FIRST_CONFIG_STATE_PATH",
        "OPENHOUSE_SECOND_AI_HANDOFF_DIR",
    ):
        if legacy_name in start_script:
            fail(f"pi-web launcher must not inject product-specific environment: {legacy_name}")

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
    if "PI_WEB_DEFAULT_CWD" not in register_script:
        fail("pi-web register-service.sh must set generic PI_WEB_DEFAULT_CWD")
    if "OPENHOUSE_PI_RUNTIME_TOKEN_FILE" not in register_script:
        fail("pi-web register-service.sh must pass the local Pi runtime token file to the same-origin WebSocket proxy")
    for legacy_name in (
        "OPENHOUSE_PI_WEB_DEFAULT_CWD",
        "OPENHOUSE_DOCS_DIR",
        "OPENHOUSE_SCRIPTS_DIR",
        "OPENHOUSE_FIRST_CONFIG_STATE_PATH",
        "OPENHOUSE_SECOND_AI_HANDOFF_DIR",
    ):
        if legacy_name in register_script:
            fail(f"pi-web service environment must not embed product-specific paths: {legacy_name}")
    if "pi-web --host" not in register_script:
        fail("pi-web register-service.sh must launch the global pi-web command")
    if "mv \"$tmp\" \"$spec\"" not in register_script:
        fail("pi-web register-service.sh must atomically install its service spec")


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
                env = service.get("env") or {}
                if env.get("SERVICE_MANAGER_CONFIG") != "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json":
                    fail("openhouse-web service must use the OpenHouse service-manager config")
                ports = service.get("ports") or []
                if not ports or ports[0].get("preferred") != 22110:
                    fail("openhouse-web service must prefer fixed port 22110")
        component_member = members.get("config/openhouse.component.json")
        if component_member is not None:
            extracted = tar.extractfile(component_member)
            if extracted is not None:
                component = json.loads(extracted.read().decode("utf-8"))
                shell_menu = component.get("shellMenu") or {}
                desktop = shell_menu.get("desktop") or {}
                smallphone_app = component.get("smallphoneApp") or {}
                if component.get("kind") != "app":
                    fail("openhouse-web component must be registered as a normal app")
                if shell_menu.get("visible") is not True or desktop.get("visible") is not True:
                    fail("openhouse-web component must be visible in the shell menu and desktop")
                if smallphone_app.get("visible") is not True:
                    fail("openhouse-web component must be visible in the app list")


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
validate_release_upgrade_contract()
validate_pi_dynamic_registration_contract()
validate_bootstrap_pi_contract(manifest)

components = by_id(component_array(manifest, "components"), "manifest.json")
payloads = by_id(component_array(payload_manifest, "payloads"), "payload-manifest.json")
validate_native_install_bundle(manifest, payload_manifest)
validate_native_runtime_asset(manifest, payload_manifest)
validate_service_control_contract()
validate_termux_package_contract()

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
