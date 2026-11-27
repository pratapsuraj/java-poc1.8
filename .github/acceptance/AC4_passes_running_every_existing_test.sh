#!/usr/bin/env bash
# AC4 on this card's pull request it passes, running every existing test (2 HBase tests may skip).
# Card SBM-2. Reads the reports the java and ui jobs upload: every existing test class has a surefire
# report with no failure or error, at most 2 tests skipped, and the UI run reports all specs passing.
# Usage: AC4_...sh <folder with surefire-reports> <ui test log> [base]
set -u
reports="${1:-reports/java}"
uilog="${2:-reports/ui/ui-test.log}"
base="${3:-origin/main}"
fail=0
[ -d "$reports" ] || { echo "FAIL AC4 no Java test reports in $reports"; fail=1; }
[ -f "$uilog" ] || { echo "FAIL AC4 no UI test log at $uilog"; fail=1; }
[ $fail -eq 0 ] || exit 1

# Existing test classes: files under src/test on main that hold a JUnit @Test.
classes=$(git grep -l '@Test' "$base" -- '*/src/test/java/*.java' '*/src/test/scala/*.scala' \
  | sed -E "s#^$base:##; s#^.*/src/test/(java|scala)/##; s#\.(java|scala)\$##; s#/#.#g")
n=0
for c in $classes; do
  n=$((n+1))
  f=$(find "$reports" -name "TEST-$c.xml" | head -1)
  if [ -z "$f" ]; then echo "FAIL AC4 no report for $c"; fail=1; fi
done
echo "info AC4 $n existing test classes on $base"

tests=0; failures=0; errors=0; skipped=0
for f in $(find "$reports" -name 'TEST-*.xml'); do
  head=$(grep -m1 -o '<testsuite [^>]*>' "$f")
  for k in tests failures errors skipped; do
    v=$(printf '%s' "$head" | grep -o " $k=\"[0-9]*\"" | grep -o '[0-9]*')
    eval "$k=\$(( $k + ${v:-0} ))"
  done
done
echo "info AC4 Java: $tests run, $failures failed, $errors errors, $skipped skipped"
[ "$tests" -gt 0 ] || { echo "FAIL AC4 no Java tests ran"; fail=1; }
[ "$failures" -eq 0 ] && [ "$errors" -eq 0 ] || { echo "FAIL AC4 Java tests failed"; fail=1; }
[ "$skipped" -le 2 ] || { echo "FAIL AC4 $skipped Java tests skipped, at most 2 (HBase) may"; fail=1; }

specs=$(git ls-tree -r --name-only "$base" -- ui/src | grep -c '\.spec\.ts$')
echo "info AC4 $specs existing UI spec files on $base"
if grep -Eq 'TOTAL: [0-9]+ SUCCESS' "$uilog" && ! grep -Eq 'TOTAL: [0-9]+ FAILED|Executed [0-9]+ of [0-9]+ .*FAILED' "$uilog"; then
  echo "ok   AC4 UI: $(grep -Eo 'TOTAL: [0-9]+ SUCCESS' "$uilog" | tail -1)"
else
  echo "FAIL AC4 UI tests did not all pass"; fail=1
fi
[ $fail -eq 0 ] && echo "ok   AC4 every existing test ran and passed"
exit $fail
