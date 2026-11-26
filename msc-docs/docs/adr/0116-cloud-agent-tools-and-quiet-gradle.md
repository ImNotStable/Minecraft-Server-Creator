# ADR 0116: Token-saving tools in cloud threads, quiet Gradle, and required use

Status: accepted, 2026-11-25, decided by Claude at Jeremiah's request.

## Context

Jeremiah asked for more ways to save Claude's usage through tools like graphify, without slowing work down, and for
CLAUDE.md to require the tools. ADR 0055 set up graphify, ponytail and the graphify guard on his PC only.

What was found on 2026-11-25:

- **Cloud project threads never ran this repository's hooks.** A thread starts in the folder above the checkout
  (`/home/user`), so Claude Code loads CLAUDE.md from the checkout but not its `.claude/settings.json`. The
  SessionStart hook (JDK 25, Maven mirror, Gradle warm-up, ADR 0038), the graphify guard and anything else in that
  file never fired. That is why the brainstorm, ledger and atmosphere threads reported graphify and ponytail missing,
  and why a first `./gradlew` in a thread failed for want of a JDK.
- **The cloud Gradle warm-up was broken anyway.** The Plugin Portal answers Fabric Loom's plugin marker with a
  redirect to Maven Central, Central answers the cloud's shared address with 429, and Gradle stops there without
  trying Fabric's own repository.
- **CLAUDE.md sent every session to a 67 KB file.** "Read `GRAPH_REPORT.md` before broad searches" costs about
  17k tokens each time it is followed. `plan.md` (55 KB) and `tools.md` (87 KB) are also read whole.
- **Gradle prints a line per task.** An incremental `./gradlew build` in the cloud printed 38 KB (about 10k tokens):
  325 `> Task` lines, and 20.8 KB of the JVM's "Picked up JAVA_TOOL_OPTIONS / _JAVA_OPTIONS" banner, which every
  forked JVM prints because the cloud sets both variables. With `-q` alone a failing test showed only "1 failed",
  not which. Bench sessions showed tokens follow turns (bench R9), and every byte of a result stays in context for
  the rest of the session.
- **One test always failed in the cloud.** `MainTest` counted the `_JAVA_OPTIONS` banner as a second line of output.
- graphify installs from PyPI (`graphifyy`) in about 10 s and builds this repository's graph in about 25 s with no
  API key. ponytail installs from its marketplace with `claude plugin install` and costs about 640 tokens per
  session, always on.

## Decision

1. **One script installs the tools: `.claude/hooks/agent-tools.sh`**, called first by `session-start.sh` in the
   cloud. It installs graphify (pinned version), builds the graph in the checkout when HEAD moved (in the background
   unless `MSC_AGENT_TOOLS_WAIT=true`), installs ponytail at user scope, and writes this repository's hooks into the
   user settings (`~/.claude/settings.json`) behind `user-hook.sh`, which does nothing when the session already runs
   in the repository. Every step is skipped when done and never fails the session.
2. **The cloud environment's setup script runs `session-start.sh`** before Claude starts, with
   `MSC_GRADLE_WARMUP=background`, so the first turn of a thread already has JDK 25, graphify, ponytail and the hooks,
   and the Gradle warm-up (minutes on a fresh container) runs alongside instead of delaying the start. The setup
   script is environment config, not in the repository; it skips itself when the checkout is missing.
3. **The Maven mirror init script keeps Fabric and NeoForge plugin markers off the Plugin Portal**
   (`excludeGroupByRegex`), so they resolve from their own repositories.
4. **A `gradle-quiet.sh` PreToolUse hook adds `-q` to `./gradlew` runs** through `updatedInput`. It only rewrites,
   never allows or blocks, and leaves a command alone that already picks a log level or console, lists tasks or
   dependencies, or sets `MSC_GRADLE_VERBOSE=1`. Where the banner variables are set, a Bash run also goes through
   `gradlew-filter.sh`, which drops the banners from stderr and keeps the exit code. `msc.java-conventions` logs
   failed tests at quiet level too, so `-q` still shows each failure with its full exception. Measured: the same full
   build prints 0 bytes when green; a failing one prints the failure and Gradle's summary (about 1 KB per failure).
   `MainTest` drops `_JAVA_OPTIONS` from its child JVM like it already did `JAVA_TOOL_OPTIONS`.
5. **CLAUDE.md requires the tools** and tells a session to say so when one is missing. It no longer points at
   `GRAPH_REPORT.md`, and it says how to read one section of a long doc (`graphify section`).
6. **Not bundled with MSC, not recommended to MSC's agents.** A scaffolded server project is a few dozen files the
   agent writes itself in one session: a code graph pays off on a codebase too big to keep in mind, and MSC's own
   tools (`api_class`, `server_logs mode=triage`, capped results) already answer the questions graphify would.
   ponytail's "smallest change" pushes against what building needs (rich, from-scratch work, ADR 0106). Bundling
   either would also make MSC install third-party code on users' machines. Building itself gets nothing from either.
   Revisit if an agent works on a large existing plugin codebase.

## Consequences

- A cloud thread starts with the same tools as a PC session, and the guard and quiet-Gradle hooks run there too.
- A failing Gradle run shows its error, warnings and failed tests without the task list. A session that needs the
  task list adds `MSC_GRADLE_VERBOSE=1` or `--console=plain`.
- graphify's version is pinned in `agent-tools.sh`; Renovate does not see it, so bump it there by hand.
- Containers built before this change keep their old setup until they are rebuilt.
