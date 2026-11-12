# ADR 0021: MSC as a Claude Code plugin

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions. Roadmap item N2.
(0019 and 0020 are taken by open PRs #34 and #35.)

## Context

ADR 0013 made the install one command, but registered a fixed `java -cp <dir>/lib/*` line with `claude mcp add`. In
all three playtests that cost about five minutes: the session's MSC was pinned to an old install or folder, a new
install needed Claude Code closed on Windows (the running core locks its jars, and `installMsc` replaced them in place),
and the threads wrote a private stdio-to-HTTP bridge to reach a fresh core without restarting.

## Decisions

1. **MSC ships as the `msc` Claude Code plugin** in `msc-plugin/`, listed by a marketplace file at the repo root
   (`.claude-plugin/marketplace.json`). The plugin holds an MCP config and a launcher, never the core itself, so the
   plugin rarely changes and a new core never needs the plugin updated.
2. **The launcher is one Java source file** run as `java MscLauncher.java` (single-file source launch), so there is no
   jar to build, commit or download, and it works the same on Windows (`java.exe`, no `.bat`). It uses only the Java 17
   API because the `java` on PATH may be older than the core's Java 25; the module compiles it with `--release 17`. It
   lives under its package path because Java 22+ refuses a source file whose path does not end with its package.
3. **The newest install wins: the one installed last.** An install is any folder under `~/.msc/core` (`MSC_CORE_HOME`)
   holding `lib/msc-core-*.jar`; the old flat `~/.msc/core/lib` counts too. Order is `installed` in
   `install.properties`. A release zip unzipped there has none, so the first launcher to see it records the time
   then. Install time, not version, because a checkout's version (`0.1.0-SNAPSHOT`) lags the releases.
4. **Every `installMsc` makes a new folder**, `msc-core-<version>-<UTC time>`, writes `source`, `installed` and the
   toolchain `java` to `install.properties`, and keeps only the newest two such folders. An older one is renamed
   before it is deleted, so a folder a running core holds open on Windows is skipped instead of half deleted. Claude
   Code no longer has to be closed to update; `/mcp` reconnect starts the new core. `claude-mcp-add.txt` is gone.
5. **Java:** `MSC_CORE_JAVA`, then the install's recorded `java`, then the launcher's own JVM, then `JAVA_HOME`; each
   must be Java 25+ (read from the JDK's `release` file). In the launcher's own JVM the core runs in-process from a
   class loader over `lib/*.jar`, so there is one process; otherwise it is a child with inherited stdio, which exits
   when Claude Code closes stdin. No install, or no Java 25, is one line on stderr, which `/mcp` shows.
6. **The workspace is the session's folder**: the launcher does not set `MSC_WORKSPACE`, so the core uses the folder
   Claude Code starts it in, and `msc_workspace` (ADR 0017) switches it in a session.
7. **The core says when a newer install is waiting.** `SetupCheck` (start notice and `msc_status`) warns
   `a newer msc-core was installed ... Reconnect msc in /mcp to use it.` when a sibling install is newer than the
   running one; the stale-checkout and repo-workspace warnings now point at `/mcp` reconnect and `msc_workspace`
   instead of `claude mcp add`.

## Checked

- `./gradlew build`; `:msc-plugin:test` starts the real core through the launcher in-process and as a child and gets
  an MCP `initialize` answer.
- Cloud, Linux: three `installMsc` runs into one root (two folders kept), then the launcher with JDK 21 first on PATH
  ran the newest install with the recorded Java 25; `msc_status` reported that folder and the session's folder.
- Not yet: Claude Code on Windows (plugin install, `/mcp` reconnect after `installMsc` with a core running). A later PC
  step.

## Consequences

- A user needs `java` 17+ on PATH for the launcher, besides the Java 25 that runs the core (often the same).
- Users of the old `claude mcp add msc` line must remove it (`claude mcp remove msc -s user`), or both servers run.
- The bench and any script that registered MSC by hand keep working: the core's command line is unchanged.
