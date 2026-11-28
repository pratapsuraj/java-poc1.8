#!/bin/bash
# SBM-5 Verify sensitivity check, source-blind: guard removal by swapping a changed file back to main's copy,
# and single-fault mutants of files the change did not touch. Each mutant runs the card's tests on a copy
# of the PR head (v-sens); M0 runs the acceptance tests on main's source (reference port).
S=C:/Work/javapoc1.8-programme/.prime/tmp/SBM-5
OLD=$S/v-old
SENS=$S/v-sens
REF=$S/v-ref
D=workflow-app/src/main/java/com/example/salesbatch/workflow/delegate
C=common/src/main/java/com/example/salesbatch/common
TESTS=ApprovalRaceAcceptanceTest,AfterCommitTest,HBaseConditionalAuditTest
OUT=$S/sensitivity
mkdir -p "$OUT"
[ -d "$SENS" ] || git clone -q "$S/v-new" "$SENS"
[ -d "$REF" ] || git clone -q "$OUT/../v-old" "$REF"

run() { # name dir tests
  ( cd "$2" && mvn -B test -pl workflow-app -am -Dtest="$3" -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
      -Dsurefire.rerunFailingTestsCount=0 > "$OUT/$1.log" 2>&1 )
  echo "== $1 exit $?" >> "$OUT/summary.txt"
  grep -E "Tests run:.*in com|<<< (FAIL|ERR)|COMPILATION ERROR|BUILD (SUCCESS|FAILURE)" "$OUT/$1.log" | sed 's/^\[[A-Z]*\] //' >> "$OUT/summary.txt"
}
reset() { ( cd "$SENS" && git checkout -q -- . ); }

: > "$OUT/summary.txt"
case "${1:-all}" in all|base)
reset; run "base-pr-head" "$SENS" "$TESTS" ;; esac
case "${1:-all}" in all|m0)
cp "$S/v-new/workflow-app/src/test/java/com/example/salesbatch/workflow/ApprovalRaceAcceptanceTest.java" "$REF/workflow-app/src/test/java/com/example/salesbatch/workflow/"
run "M0-main-source-with-acceptance-tests" "$REF" ApprovalRaceAcceptanceTest ;; esac
case "${1:-all}" in all|m)
reset; cp "$OLD/$D/SparkJobDelegate.java" "$SENS/$D/"; run "M1-SparkJobDelegate-from-main" "$SENS" "$TESTS"
reset; cp "$OLD/$D/RunFailure.java" "$SENS/$D/"; run "M2-RunFailure-from-main" "$SENS" "$TESTS"
reset; cp "$OLD/$C/HBaseSalesRepository.java" "$SENS/$C/"; run "M3-HBaseSalesRepository-from-main" "$SENS" "$TESTS"
reset; sed -i 's/+ r.getTotalQty() + "," + r.getTotalAmount/+ r.getOrderCount() + "," + r.getTotalAmount/' "$SENS/$D/PublishDelegate.java"; run "M4-report-qty-column-swapped" "$SENS" "$TESTS"
reset; sed -i 's/"REJECTED"/"COMPLETED"/' "$SENS/$D/RejectDelegate.java"; run "M5-reject-writes-COMPLETED" "$SENS" "$TESTS"
reset; sed -i 's/updateAudit(pid, "COMPLETED"/updateAudit(pid, "DONE"/' "$SENS/$D/PublishDelegate.java"; run "M6-publish-writes-DONE" "$SENS" "$TESTS"
reset; sed -i 's/repository.updateAudit(pid, "RUNNING", null);/\/\/ mutant: RUNNING not written/' "$SENS/$D/IngestDelegate.java"; run "M7-ingest-drops-RUNNING" "$SENS" "$TESTS"
reset ;; esac
