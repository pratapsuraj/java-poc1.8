#!/usr/bin/env bash
# AC3 no existing source, test or build file changes.
# Card SBM-2. Every file the branch changes against main is new, and lies under .github/ or docs/.
set -u
base="${1:-origin/main}"
fail=0
git rev-parse --verify -q "$base" >/dev/null || { echo "FAIL AC3 base $base not found (check out with fetch-depth: 0)"; exit 1; }
while IFS=$'\t' read -r status path rest; do
  [ -z "$status" ] && continue
  case "$status" in
    A) ;;
    *) echo "FAIL AC3 existing file changed ($status): $path${rest:+ -> $rest}"; fail=1; continue ;;
  esac
  case "$path" in
    .github/*|docs/*) ;;
    *) echo "FAIL AC3 new file outside .github/ and docs/: $path"; fail=1 ;;
  esac
done < <(git diff --name-status --no-renames "$base"...HEAD)
[ $fail -eq 0 ] && echo "ok   AC3 only new files under .github/ and docs/ against $base"
exit $fail
