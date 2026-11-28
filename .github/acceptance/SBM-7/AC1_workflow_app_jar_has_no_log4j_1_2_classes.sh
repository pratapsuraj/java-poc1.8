#!/usr/bin/env bash
# AC1 workflow-app jar has no log4j 1.2 classes.
# Card SBM-7. Needs the shaded jar: mvn -B package -DskipTests
set -u
. "$(dirname "$0")/log4j12_check.sh"
check_no_log4j12 AC1 "${1:-workflow-app/target/workflow-app.jar}"
