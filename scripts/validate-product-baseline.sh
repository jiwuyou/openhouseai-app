#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
product_ref="${OPENHOUSE_PRODUCT_BASELINE_REF:-refs/remotes/origin/feature/wuxianpi-ai-web-ui}"
required_commits=(
  a8844dd1
  fd30e815
)

cd "$repo_dir"

[[ -z "$(git status --porcelain --untracked-files=normal)" ]] \
  || { printf 'Product baseline worktree must be clean\n' >&2; exit 1; }
git diff --check

for commit in "${required_commits[@]}"; do
  git cat-file -e "${commit}^{commit}" 2>/dev/null \
    || { printf 'Required product commit is unavailable: %s\n' "$commit" >&2; exit 1; }
  git merge-base --is-ancestor "$commit" HEAD \
    || { printf 'Product baseline does not contain required commit: %s\n' "$commit" >&2; exit 1; }
done

head_sha="$(git rev-parse HEAD)"
if [[ "$product_ref" == "HEAD" ]]; then
  product_sha="$head_sha"
else
  git show-ref --verify --quiet "$product_ref" \
    || { printf 'Product baseline ref is unavailable: %s\n' "$product_ref" >&2; exit 1; }
  product_sha="$(git rev-parse "$product_ref")"
fi
[[ "$head_sha" == "$product_sha" ]] \
  || { printf 'Formal builds must use %s (%s), found %s\n' "$product_ref" "$product_sha" "$head_sha" >&2; exit 1; }

"$repo_dir/scripts/validate-operit-lean-source-set.sh"

settings_file="$repo_dir/operit-feature/src/main/java/com/ai/assistance/operit/ui/features/settings/screens/SettingsScreen.kt"
[[ "$(rg -c '^\s*SettingsEntry\($' "$settings_file")" == "2" ]] \
  || { printf 'Lean SettingsScreen must contain exactly two entries\n' >&2; exit 1; }
rg -q 'navigateToModelConfig' "$settings_file" \
  || { printf 'Lean SettingsScreen is missing model configuration\n' >&2; exit 1; }
rg -q 'navigateToThemeSettings' "$settings_file" \
  || { printf 'Lean SettingsScreen is missing theme settings\n' >&2; exit 1; }

printf 'Product baseline gate passed: commit=%s dirty=false ref=%s\n' "$head_sha" "$product_ref"
