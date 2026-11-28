#!/usr/bin/env bash
# AC2 Spark job jar has no log4j 1.2 classes.
# Card SBM-7. Needs the shaded jar: mvn -B package -DskipTests
set -u
. "$(dirname "$0")/log4j12_check.sh"
check_no_log4j12 AC2 "${1:-spark-job/target/spark-job-1.0.0-SNAPSHOT.jar}"
