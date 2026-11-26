#!/bin/bash
# Runs a Gradle wrapper command ($@) with the JVM's "Picked up JAVA_TOOL_OPTIONS/_JAVA_OPTIONS" banners dropped from
# stderr (ADR 0116). The cloud sets both variables, and every forked JVM prints them: about 1.4 KB per test task,
# half of a full build's output. gradle-quiet.sh puts this in front of ./gradlew where those variables are set.
"$@" 2> >(grep -v --line-buffered -E '^(NOTE: )?Picked up (JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS):' >&2)
status=$?
wait $! 2>/dev/null
exit $status
