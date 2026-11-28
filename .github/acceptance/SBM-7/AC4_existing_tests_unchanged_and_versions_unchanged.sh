#!/usr/bin/env bash
# AC4 existing tests unchanged and pass, new tests pass, Java 8 and EMR 5.36 versions unchanged.
# Card SBM-7. This script checks the parts the pipeline's other jobs do not:
#  - no existing test file is changed, deleted or renamed against the base (new files are allowed);
#  - the root pom.xml keeps Java 8 and the EMR 5.36 versions (Spark 2.4.8, HBase 1.4.13, Hadoop 2.10.1, Scala 2.11.12).
# "Existing tests pass" is the pipeline's java job; "new tests pass" is AC1 to AC3 in the same run.
set -u
base="${1:-origin/main}"
fail=0
git rev-parse --verify -q "$base" >/dev/null || { echo "FAIL AC4 base $base not found (check out with fetch-depth: 0)"; exit 1; }

while IFS=$'\t' read -r status path rest; do
  [ -z "$status" ] && continue
  case "$path" in
    */src/test/*|ui/src/*.spec.ts)
      [ "$status" = A ] || { echo "FAIL AC4 existing test file changed ($status): $path${rest:+ -> $rest}"; fail=1; } ;;
  esac
done < <(git diff --name-status --no-renames "$base"...HEAD)

expect() {
  local prop="$1" want="$2" got
  got="$(sed -n "s:.*<$prop>\(.*\)</$prop>.*:\1:p" pom.xml | head -1)"
  if [ "$got" != "$want" ]; then
    echo "FAIL AC4 pom.xml $prop is '$got', expected '$want'"; fail=1
  fi
}
expect java.version 1.8
expect spark.version 2.4.8
expect hbase.version 1.4.13
expect hadoop.version 2.10.1
expect scala.version 2.11.12
expect scala.binary.version 2.11

[ $fail -eq 0 ] && echo "ok   AC4 no existing test file changed against $base; Java 8 and EMR 5.36 versions unchanged"
exit $fail
