#!/bin/bash
# PreToolUse hook (ADR 0116): adds -q to a Gradle wrapper run, so a build prints its errors, warnings and failed tests
# (msc.java-conventions logs those at quiet level too) but not hundreds of "> Task" lines. An incremental cloud
# `./gradlew build` dropped from 38 KB of output to nothing when green.
#
# Leaves the command alone when it already picks a log level or console, lists something (tasks, dependencies,
# properties, help, a dry run), or sets MSC_GRADLE_VERBOSE=1. Only rewrites; never allows or blocks anything.
# Where the cloud's JAVA_TOOL_OPTIONS or _JAVA_OPTIONS are set, a Bash run also goes through gradlew-filter.sh, which
# drops the banner every forked JVM prints for them. A no-op without Python.
filter=""
if [ -n "${JAVA_TOOL_OPTIONS:-}${_JAVA_OPTIONS:-}" ]; then
  filter="bash \"$(cd "$(dirname "$0")" && pwd)/gradlew-filter.sh\" "
fi
export MSC_GRADLE_FILTER="$filter"
py="$(command -v python3 || command -v python)" || exit 0
exec "$py" -c '
import json, os, re, sys
event = json.load(sys.stdin)
args = event.get("tool_input") or {}
cmd = args.get("command")
if not isinstance(cmd, str):
    sys.exit(0)
wrapper = re.compile(r"((?:^|[\s;&|(])(?:\.[/\\]|\S*/)gradlew(?:\.bat)?)(?=\s|$)")
skip = re.compile(r"(^|\s)(-q|--quiet|-w|--warn|-i|--info|-d|--debug|--console\S*|--scan|-m|--dry-run|--help|-h|-v|--version"
                  r"|tasks|dependencies|dependencyInsight|properties|help|MSC_GRADLE_VERBOSE=1)(?=\s|$)")
if not wrapper.search(cmd) or skip.search(cmd):
    sys.exit(0)
prefix = os.environ.get("MSC_GRADLE_FILTER", "") if event.get("tool_name") == "Bash" else ""
args = dict(args, command=wrapper.sub(lambda m: re.sub(r"(\S+)$", lambda w: prefix + w.group(1), m.group(1)) + " -q", cmd))
print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "updatedInput": args}}))
'
