#!/bin/sh
# SBM-5 Verify: side-by-side against real HBase (container sbm5-verify-hbase), main then the PR head.
S=C:/Work/javapoc1.8-programme/.prime/tmp/SBM-5
for v in ${@:-old new}; do
  cp "$S/harness/SbsHBaseHarnessTest.java" "$S/v-$v/workflow-app/src/test/java/com/example/salesbatch/workflow/"
  cd "$S/v-$v" || exit 2
  JAVA_TOOL_OPTIONS="-Dsbs.out=$S/sbs-hbase-$v.txt -Dsbs.reports=$S/sbs-hbase-reports-$v" \
  mvn -B test -pl workflow-app -am -Dtest=SbsHBaseHarnessTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
    -Dsurefire.rerunFailingTestsCount=0 > "$S/sbs-hbase-$v.log" 2>&1
  echo "$v exit $?"
done
