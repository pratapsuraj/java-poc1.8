#!/usr/bin/env bash
# AC2 it fails when a Java or UI test fails.
# Card SBM-2. Static half: nothing in the pipeline swallows a test failure.
# Observed half (sensitivity, in the card file): a scratch branch with one failing Java test, then one
# failing UI spec, gives a red run each time.
set -u
wf="${1:-.github/workflows/ci.yml}"
fail=0
[ -f "$wf" ] || { echo "FAIL AC2 $wf does not exist"; exit 1; }
if grep -Eq 'continue-on-error:[[:space:]]*true' "$wf"; then echo "FAIL AC2 a step or job continues on error"; fail=1; else echo "ok   AC2 no continue-on-error"; fi
if grep -Eq '\|\|[[:space:]]*(true|:|exit 0)' "$wf"; then echo "FAIL AC2 a command hides its exit code"; fail=1; else echo "ok   AC2 no command hides its exit code"; fi
if grep -Eq 'testFailureIgnore|maven\.test\.failure\.ignore|skipTests|maven\.test\.skip|-DskipTests' "$wf"; then echo "FAIL AC2 Maven is told to ignore or skip tests"; fail=1; else echo "ok   AC2 Maven does not ignore or skip tests"; fi
# Decision 2a: a failed Java test is retried at most 2 times.
reruns=$(grep -Eo 'rerunFailingTestsCount=[0-9]+' "$wf" | head -1 | cut -d= -f2)
if [ -z "$reruns" ]; then echo "FAIL AC2 the Java job sets no retry count (decision: up to 2)"; fail=1
elif [ "$reruns" -gt 2 ]; then echo "FAIL AC2 retries $reruns times, more than 2"; fail=1
else echo "ok   AC2 a failed Java test is retried at most $reruns times"; fi
exit $fail
