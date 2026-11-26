#!/bin/bash
# Runs a Gradle wrapper command ($@) without two kinds of noise (ADR 0116, 0120), keeping its exit code:
# - stderr: the JVM's "Picked up JAVA_TOOL_OPTIONS/_JAVA_OPTIONS" banner, which every forked JVM prints where the cloud
#   sets those variables (about 1.4 KB per test task, half of a full build's output);
# - stdout: NeoForge's NFRT progress lines (about 5 KB each time the configuration cache misses).
# gradle-quiet.sh puts this in front of ./gradlew where the banner variables are set.
nfrt=$'(\e\\[[0-9;]*m.*(Started working on|Completed|Used cache of))|^Loaded [0-9]+ artifacts from |Total runtime: [0-9.]+s|^ +.{1,3} https?://|Waiting for lock on |^\\.+ *$'
"$@" > >(grep -v --line-buffered -aE "$nfrt") \
  2> >(grep -v --line-buffered -E '^(NOTE: )?Picked up (JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS):' >&2)
status=$?
wait 2>/dev/null
exit $status
