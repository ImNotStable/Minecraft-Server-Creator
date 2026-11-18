# ADR 0055: Agent efficiency tooling

Status: accepted, 2026-11-18, decided by Claude at Jeremiah's request.

## Context

Jeremiah asked that ponytail and graphify actually be used in this project, that two Instagram reels about Claude
Code be checked for anything useful, and that sessions keep looking for tools that cut token use.

What was found on his PC: ponytail is installed and its SessionStart hook runs in every session. graphify is
installed, but its graph was five days and 238 commits behind `main`, and nothing pointed a session at it except a
line in CLAUDE.md. Most threads work in worktrees, which have no `graphify-out/` of their own, so even graphify's
own hooks would have found no graph there.

The reels: one is a per-task model guide (cheap models for search and boilerplate, strong ones for planning,
security and hard bugs, review in a separate session). The other describes three Claude Code "mods": a gatekeeper
hook that saves usage, Claude planning for Claude, and a live dashboard of agents. Neither names a specific repo.

## Decision

1. **A graphify guard hook, committed in the repo.** `.claude/hooks/graphify-guard.sh` runs graphify's own
   `hook-guard` before Grep, a search command, and a Read or Glob of source. It finds the main checkout's graph
   from any worktree (`git rev-parse --git-common-dir`, then `GRAPHIFY_OUT`), adds a short advisory note with the
   graph nodes that match the call, and never blocks. graphify caps it at 5 notes per agent. Where graphify or the
   graph is missing it exits 0 and prints nothing.
2. **The graph is refreshed from the main checkout after merges** (`graphify update .`), and was refreshed now.
3. **Model by task, as guidance only.** Search goes to an Explore subagent on Haiku; review runs in a session that
   did not write the code. Effort stays the user's to set.
4. **Left out:** Bash output compressors (rtk and the like), API proxies, a live dashboard, and graphify's project
   install. Reasons are in [agent-efficiency.md](../agent-efficiency.md).
5. **Standing task:** sessions keep looking for token-saving tools and record each one, adopted or not, in
   agent-efficiency.md.

## Consequences

- A session on the PC is pointed at the graph on its first search or source read, in a worktree too, for about
  0.3 s per matching call.
- Cloud sessions are unchanged: the hook finds no graphify and does nothing.
- The graph goes stale between refreshes. The guard's own staleness check skips files newer than the graph.
