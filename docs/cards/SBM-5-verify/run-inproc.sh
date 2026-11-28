#!/bin/sh
# SBM-5 Verify: in-process side-by-side, main (v-old) then the PR head (v-new). Usage: run-inproc.sh [old new]
S=C:/Work/javapoc1.8-programme/.prime/tmp/SBM-5
for v in ${@:-old new}; do
  cd "$S/v-$v" || exit 2
  JAVA_TOOL_OPTIONS="-Dsbs.out=$S/sbs-inproc-$v.txt -Dsbs.reports=$S/sbs-reports-$v" \
  mvn -B test -pl workflow-app -am -Dtest=SbsHarnessTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
    -Dsurefire.rerunFailingTestsCount=0 > "$S/sbs-inproc-$v.log" 2>&1
  echo "$v exit $?"
done
