#!/bin/bash
# Runs one of this repository's hooks from the user settings (ADR 0116), for a cloud session that started outside
# the repository. Does nothing when the session runs in the repository, because its own settings run the hook then.
# Usage: user-hook.sh <hook script> [args...]
repo="$(cd "$(dirname "$0")/../.." && pwd)"
[ "$(cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null && pwd)" = "$repo" ] && exit 0
script="$1"
shift
CLAUDE_PROJECT_DIR="$repo" exec bash "$repo/.claude/hooks/$script" "$@"
