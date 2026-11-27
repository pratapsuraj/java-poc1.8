#!/usr/bin/env bash
# AC1 a pull request or push to main starts the pipeline: mvn -B clean test and the UI tests.
# Card SBM-2. Reads the pipeline definition; the run on the card's pull request is the observed half.
set -u
wf="${1:-.github/workflows/ci.yml}"
fail=0
check() { # description, extended regex
  if grep -Eq -- "$2" "$wf"; then echo "ok   AC1 $1"; else echo "FAIL AC1 $1"; fail=1; fi
}
[ -f "$wf" ] || { echo "FAIL AC1 $wf does not exist"; exit 1; }
check "runs on pull requests"                 '^[[:space:]]*pull_request:'
check "runs on push"                          '^[[:space:]]*push:'
check "push is limited to main"               '^[[:space:]]*branches:[[:space:]]*\[[[:space:]]*main[[:space:]]*\]|^[[:space:]]*-[[:space:]]*main[[:space:]]*$'
check "runs mvn -B clean test"                'mvn -B clean test'
check "installs the UI's dependencies"        'npm ci'
check "runs the UI tests once, headless"      'ng test --watch=false --browsers=ChromeHeadless'
check "runs the UI tests in ui/"              'working-directory:[[:space:]]*ui'
exit $fail
