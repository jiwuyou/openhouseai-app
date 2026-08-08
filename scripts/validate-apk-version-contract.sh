#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
properties="$repo_dir/gradle.properties"
app_gradle="$repo_dir/app/build.gradle"
native_gradle="$repo_dir/native-app/build.gradle"

[[ -f "$properties" && -f "$app_gradle" && -f "$native_gradle" ]] || {
  printf 'APK version contract files are missing\n' >&2
  exit 1
}

version_code="$(sed -n 's/^openhouseVersionCode=\([0-9][0-9]*\)$/\1/p' "$properties" | head -n 1)"
version_name="$(sed -n 's/^openhouseVersionName=\([^[:space:]]*\)$/\1/p' "$properties" | head -n 1)"
[[ "$version_code" =~ ^[0-9]+$ && "$version_code" -ge 1 ]] || {
  printf 'openhouseVersionCode must be a positive integer\n' >&2
  exit 1
}
[[ "$version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.+][A-Za-z0-9.-]+)?$ ]] || {
  printf 'openhouseVersionName is not a valid release version: %s\n' "$version_name" >&2
  exit 1
}

grep -Fq 'project.properties.openhouseVersionCode' "$app_gradle" || {
  printf 'All-in-One build.gradle does not read the canonical versionCode\n' >&2
  exit 1
}
grep -Fq 'project.properties.openhouseVersionName' "$app_gradle" || {
  printf 'All-in-One build.gradle does not read the canonical versionName\n' >&2
  exit 1
}
grep -Fq 'project.properties.openhouseVersionCode' "$native_gradle" || {
  printf 'Native build.gradle does not read the canonical versionCode\n' >&2
  exit 1
}
grep -Fq 'project.properties.openhouseVersionName' "$native_gradle" || {
  printf 'Native build.gradle does not read the canonical versionName\n' >&2
  exit 1
}

printf 'APK version contract passed: versionCode=%s versionName=%s\n' "$version_code" "$version_name"
