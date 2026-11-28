#!/usr/bin/env bash
# AC3 workflow-app log lines reach the console.
# Card SBM-7. Starts the shaded workflow-app jar as an operator would (java -jar), reads what it writes to
# the console for up to 90 seconds, then stops it. Passes when at least one log line in the pattern of
# workflow-app/src/main/resources/logback.xml ("HH:mm:ss.SSS LEVEL logger - message") reaches stdout,
# and log4j's "No appenders could be found" warning (log lines being dropped) is not printed.
# Needs the shaded jar: mvn -B package -DskipTests
set -u
jar="${1:-workflow-app/target/workflow-app.jar}"
[ -f "$jar" ] || { echo "FAIL AC3 jar not found: $jar (run mvn -B package -DskipTests first)"; exit 1; }

work="$(mktemp -d)"
out="$work/stdout.log"; err="$work/stderr.log"
port="${AC3_PORT:-18080}"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Dserver.port="$port" -Dreport.dir="$work/reports" -jar "$jar" >"$out" 2>"$err" &
pid=$!
stop() { kill "$pid" 2>/dev/null; sleep 2; kill -9 "$pid" 2>/dev/null; wait "$pid" 2>/dev/null; }

line_re='^[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3} (TRACE|DEBUG|INFO|WARN|ERROR) +[^ ]+ - '
for _ in $(seq 1 90); do
  grep -Eq "$line_re" "$out" && grep -q 'Started' "$out" && break
  kill -0 "$pid" 2>/dev/null || break
  sleep 1
done
stop

fail=0
if ! grep -Eq "$line_re" "$out"; then
  echo "FAIL AC3 no log line in logback.xml's pattern reached stdout of java -jar $jar"
  fail=1
fi
if grep -q 'log4j:WARN No appenders could be found' "$out" "$err"; then
  echo "FAIL AC3 log4j printed 'No appenders could be found': log lines are being dropped"
  fail=1
fi
if [ $fail -ne 0 ]; then
  echo "       stdout (first 10 lines):"; head -10 "$out" | sed 's/^/       /'
  echo "       stderr (first 10 lines):"; head -10 "$err" | sed 's/^/       /'
  rm -rf "$work"; exit 1
fi
echo "ok   AC3 $(grep -Ec "$line_re" "$out") log lines reached the console, for example:"
grep -E "$line_re" "$out" | head -3 | sed 's/^/       /'
rm -rf "$work"
exit 0
