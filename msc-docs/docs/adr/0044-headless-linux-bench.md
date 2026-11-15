# ADR 0044: Headless Linux and cloud benchmark runs

Status: accepted, 2026-11-15, decided by Claude.

## Context

Every benchmark run so far was made on the owner's Windows PC ([ADR 0004](0004-bench.md)). The bench should also run
unattended on a Linux box (the Ubuntu machine `scripts/setup-ubuntu.sh` sets up) and in a Claude Code cloud
container, so it stops depending on the PC. A first attempt in a cloud session failed in three ways:

- `claude -p --output-format json` printed one result object and no init event there, so the runner saw no MCP server
  connected and flagged every toolset attempt "MCP not connected", whether or not msc was there.
- The agent ran with another system prompt and other tools: a `claude -p` started from inside a Claude Code session
  inherits that session's variables (`CLAUDECODE`, `CLAUDE_CODE_*`, `CCR_*`) and runs as part of it.
- `msc.core.Main` processes left on port 25590 broke later toolset attempts: Claude Code does not always stop its MCP
  servers when it exits, a core it leaves is no longer its child, so no tree kill reaches it, and the next attempt's
  core cannot take the port. A cloud session's own msc plugin may hold the port too.

## Decision

1. **The agent prints stream JSON.** The default and the shipped `agent.command` use
   `--output-format stream-json --verbose`, whose `system/init` event always lists the MCP servers with their status.
   The result parser keeps reading every format; it now also records the servers the init event listed as not
   connected, with their status (`mcpNotConnected`, such as `msc (failed)`), so a refused attempt says why: the status
   the server had, or that the output named no MCP server at all and needs `stream-json --verbose`. A logged tool call
   still proves the server was there, and the 3 tries stay.
2. **The agent does not inherit a parent Claude Code session.** Before the mode's `env`, the runner drops
   `CLAUDECODE`, `CLAUDE_*` and `CCR_*` from the agent's environment, except what picks the account or provider
   (`CLAUDE_CODE_OAUTH_TOKEN`, `CLAUDE_CONFIG_DIR`, `CLAUDE_SESSION_INGRESS_TOKEN_FILE`, `CLAUDE_CODE_USE_*`,
   `CLAUDE_CODE_SKIP_*`). `ANTHROPIC_*` stays. A mode's `env` can set any of them back.
3. **The bench stops the cores it started, and only those.** The toolset MCP config template starts core with
   `-Dmsc.bench.attempt={attemptDir}` first on its command line. After every toolset attempt the runner stops the
   processes carrying exactly that argument, with their descendants. Before a toolset attempt it checks the core port:
   when it is taken, it stops the cores of earlier bench attempts (two runs on the same ports cannot work anyway); when
   it is still taken, the attempt fails at once naming the msc-core processes the bench did not start and asking to
   stop them or set `corePort`. It never stops a process it did not mark.
4. **`run --isolate` puts each agent in its own PID namespace (Linux).** The first proof run lost its baseline attempt
   when the agent cleaned up with `killall -9 java`: that killed the bench JVM, every Gradle daemon and the session's
   own msc-core. With `--isolate` the agent command is prefixed with `unshare --pid --fork --mount-proc --kill-child`
   (plus `--user --map-current-user` when not root, so Claude Code does not run as root), tried once with `true`
   before the run and refused with the reason when the system cannot (not Linux, user namespaces off). The agent sees
   only its own processes, and when it exits the kernel ends what it left, msc-core included. Opt-in, since Windows
   has no such thing and the PC's runs stay comparable; recommended on every shared Linux machine.
5. **`run --model M`** replaces the `--model` value of `agent.command` (or adds one), so a cheap smoke run needs no
   config copy. The pinned model in `bench.json` stays the one comparisons use. The documented minimal selection is
   one task, one repeat: `run --tasks game-sky-sprint --repeat 1` (Minestom, no EULA, the whole game) or
   `texture-ruby-item` (no server, no Gradle build). No separate `--quick` flag.

## Consequences

- `agent-stdout.json` now holds one JSON event per line. `report` and old run folders are unaffected: the parser reads
  all three shapes, and `results.jsonl` records from before this lack `mcpNotConnected`, which reads as empty.
- A user's own `bench.json` that still uses `--output-format json` works as before; only its MCP refusals are less
  precise, and they say what to change.
- Proof run in a Claude Code cloud container (Linux), 2026-11-15: see the pull request that added this ADR.
- Not verified: `--isolate` on Ubuntu 24.04 with its AppArmor user-namespace restriction (the trial run refuses it
  with the reason), the Ubuntu box itself (its setup script installs the same JDK and Claude Code the container has), and
  Paper tasks headless, which need the owner's EULA acceptance on that machine.
