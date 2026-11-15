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

## Amendment, 2026-11-15: one ownership rule for every kill (#587)

Decision 3 marked core with `-Dmsc.bench.attempt=` on its command line, and #577 added kills of whatever ran in an
attempt folder or named it on its command line. Both reached processes the bench did not start: a user's shell or
tmux pane `cd`'d into an attempt, a `tail -f` of its log, an editor on its files, and, through the core port check,
anything with the marker anywhere on its command line, another bench run's live cores included.

Every kill now goes through one rule, `AttemptProcesses`: a process is the run's when its environment holds
`MSC_BENCH_RUN` with this run's id (a random UUID per run) and `MSC_BENCH_ATTEMPT` with an attempt folder inside the
one being cleared. The runner sets both on every process it starts for an attempt (the agent, the server, each check
process) and adds them to the `env` of every server in the filled MCP config, so msc-core has them however Claude
Code passes its environment on. Children inherit them, through `nohup`, `setsid`, `&` and a Gradle build's forked
server alike. The bench reads them from `/proc/<pid>/environ`, the environment a process started with. The leftovers
after an attempt, the Ctrl-C hook, the ports step and the core port check all use this rule, and
`-Dmsc.bench.attempt=` is gone from the template.

- Where the environment cannot be read (Windows, macOS, another user's process, a process already gone), nothing is
  proven and nothing is killed: the port is refused with a message saying so.
- A Gradle daemon an attempt started carries its marks but stays, as before, for later builds to reuse; a server it
  forks carries the marks of the build that forked it and is killed.
- A process that clears its environment (`env -i`) is killed only together with the marked process it runs under.

Chosen over "a descendant that started after the attempt began": a detached process is no longer a descendant of
anything the bench can see, and a start time alone also matches a user's shell opened during the attempt.

Not verified: that Claude Code passes its own environment to stdio MCP servers (the config `env` covers it either
way), and that a reused Gradle daemon gives a forked server the current build's environment rather than its own start
environment (if not, that server is still the run's, and the Ctrl-C hook or the next ports step kills it).

## Amendment, 2026-11-15: a command-line proof where no environment is shown (#595)

The amendment above left Windows and macOS with no proof at all, so an msc-core that Claude Code leaves running there
failed every later toolset attempt of the run, a regression from decision 3's command-line marker. The owner's full
reruns are made on Windows.

The run now also marks msc-core's command line, with the same strictness as the environment:

- `BenchRunner.withMarks` puts `-Dmsc.bench.run=<run id>` and `-Dmsc.bench.attempt=<attempt folder>` first in the
  `args` of every server of the filled MCP config whose command is the Java launcher (`java`, `javaw`, with or without
  a folder and `.exe`), next to the `env` marks. Other commands are left as they are, since only Java reads `-D`.
- `AttemptProcesses` reads the environment first. Only when the system shows none of a process does it look at the
  process's whole arguments: the process is the run's when one argument is exactly this run's `-Dmsc.bench.run=` and
  it has at least one `-Dmsc.bench.attempt=` argument, each naming an attempt inside the folder being cleared. Text
  that merely contains a marker (a shell script naming it, a longer argument, the old `line.contains` test) never
  counts, and another run's id never matches. Where the environment is shown it decides alone.
- The arguments come from `ProcessHandle.Info.arguments()` (Linux, macOS). Windows gives Java no argument list, so
  there one `Get-CimInstance Win32_Process` query through `powershell.exe` per pass gives each process's command line,
  split by the C runtime's rules (the Java launcher's own). A failed query proves nothing, and nothing is killed.
- Only msc-core is marked this way: the other processes of an attempt (the agent, a server it detached) are still
  proven only by their environment, so on Windows and macOS a server left on the bench's ports still fails the next
  attempt naming the port.

Tested on Linux with stand-in processes and a reader that shows no environment, and with a `Win32_Process` answer for
real stand-ins. Not verified: the query and the kill on a real Windows machine, and macOS.
