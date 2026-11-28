#!/usr/bin/env bash
# Card SBM-7, shared by AC1 and AC2: does a jar hold log4j 1.2 classes?
# "log4j 1.2 classes" are classes from the log4j:log4j artifact, and its SLF4J binding slf4j-log4j12.
# The SLF4J bridge log4j-over-slf4j also puts classes under org/apache/log4j/ (Logger, Level and the like);
# they are SLF4J's own code, not log4j 1.2's, so the 30 names it ships are allowed. Any other
# org/apache/log4j/ entry (Hierarchy, net/SocketServer, jdbc/JDBCAppender, chainsaw/ ...) fails.
# Usage: check_no_log4j12 <criterion> <jar>

BRIDGE_CLASSES='
org/apache/log4j/Appender.class
org/apache/log4j/AppenderSkeleton.class
org/apache/log4j/BasicConfigurator.class
org/apache/log4j/Category.class
org/apache/log4j/ConsoleAppender.class
org/apache/log4j/FileAppender.class
org/apache/log4j/helpers/LogLog.class
org/apache/log4j/helpers/NullEnumeration.class
org/apache/log4j/Layout.class
org/apache/log4j/Level.class
org/apache/log4j/Log4jLoggerFactory.class
org/apache/log4j/Logger.class
org/apache/log4j/LogManager.class
org/apache/log4j/MDC.class
org/apache/log4j/NDC.class
org/apache/log4j/PatternLayout.class
org/apache/log4j/Priority.class
org/apache/log4j/PropertyConfigurator.class
org/apache/log4j/RollingFileAppender.class
org/apache/log4j/SimpleLayout.class
org/apache/log4j/spi/Configurator.class
org/apache/log4j/spi/ErrorHandler.class
org/apache/log4j/spi/Filter.class
org/apache/log4j/spi/HierarchyEventListener.class
org/apache/log4j/spi/LoggerFactory.class
org/apache/log4j/spi/LoggerRepository.class
org/apache/log4j/spi/LoggingEvent.class
org/apache/log4j/spi/OptionHandler.class
org/apache/log4j/WriterAppender.class
org/apache/log4j/xml/DOMConfigurator.class
'

check_no_log4j12() {
  local ac="$1" jar="$2" entries bad
  [ -f "$jar" ] || { echo "FAIL $ac jar not found: $jar (run mvn -B package -DskipTests first)"; return 1; }
  entries="$(unzip -Z1 "$jar")" || { echo "FAIL $ac cannot list $jar"; return 1; }
  bad="$(
    {
      printf '%s\n' "$entries" | grep -E '^org/apache/log4j/.*[^/]$' | grep -vxF -f <(printf '%s\n' "$BRIDGE_CLASSES" | grep -v '^$')
      printf '%s\n' "$entries" | grep -E '^META-INF/maven/log4j/log4j/|^META-INF/maven/org\.slf4j/slf4j-log4j12/|^org/slf4j/impl/Log4jLoggerFactory\.class$'
    } | sort -u
  )"
  if [ -n "$bad" ]; then
    echo "FAIL $ac $jar holds $(printf '%s\n' "$bad" | wc -l | tr -d ' ') log4j 1.2 entries, for example:"
    printf '%s\n' "$bad" | head -10 | sed 's/^/       /'
    return 1
  fi
  echo "ok   $ac $jar holds no log4j 1.2 classes"
}
