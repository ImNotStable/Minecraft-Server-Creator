#!/usr/bin/env bash
# PreToolUse hook (ADR 0055): before a raw search or source read, point Claude at the code graph.
# Advisory only and capped by graphify itself (5 nudges per agent). A no-op where graphify or the graph is missing,
# like cloud sessions. Worktrees have no graphify-out/ of their own, so it uses the main checkout's.
command -v graphify >/dev/null 2>&1 || exit 0
common=$(git -C "${CLAUDE_PROJECT_DIR:-.}" rev-parse --path-format=absolute --git-common-dir 2>/dev/null) || exit 0
export GRAPHIFY_OUT="${GRAPHIFY_OUT:-${common%/.git}/graphify-out}"
[ -f "$GRAPHIFY_OUT/graph.json" ] || exit 0
graphify hook-guard "$1" || true
