# ADR 0013: One-command install and first-use fixes

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions.

## Context

"Usable" means: add the MSC MCP server to Claude Code with one command, start Claude Code in an empty folder, ask for a server, and get a working result with no hand-holding. Before this, a user had to build two modules, register `java -cp ...` with `MSC_WORKSPACE`, and copy the agent jar into `.msc/agent` or set `MSC_AGENT_JAR`. Running the whole flow through MCP stdio as a new user on a real Paper 26.3 server (cloud, 2026-11-12) also found friction in the tools themselves.

## Decisions

1. **`./gradlew :msc-core:installMsc` is the install.** It syncs the msc-core distribution to `~/.msc/core` (`-Pmsc.installDir` overrides) and prints one `claude mcp add msc --scope user -- "<java>" -cp "<dir>/lib/*" io.github.imnotstable.msc.core.Main` line, also saved in `claude-mcp-add.txt`. The command is the Gradle toolchain's Java by absolute path with a `lib/*` class path: no JAVA_HOME, no PATH entry, no `.bat` (which Claude Code on Windows cannot spawn without `cmd /c`). The folder is outside `build/`, so `clean` and rebuilds do not break a running Claude Code. Because `Sync` deletes extra files, the task refuses a non-empty folder that holds no earlier install.
2. **The distribution ships the agents.** `agents/` in the distribution holds the Paper agent (shadow jar) and the Fabric agent (Loom jar). The core finds its install folder from its own jar's location (`<dir>/lib/msc-core-*.jar`) and `server_setup` looks there after `MSC_AGENT_JAR` and `.msc/agent`. Release zips (`distZip`) carry the same layout. Minestom's agent stays inside the `minestom-server` template.
3. **The workspace is the folder Claude Code runs in** (unchanged default), and `project_scaffold` now treats `.mcp.json`, `.claude`, `.git`, `.idea` and `.vscode` like `.msc`: a folder holding only those counts as empty.
4. **A clean dev start reads `no errors or warnings`.** The log condenser drops Paper's root/administrator and offline-mode banners and the Windows oshi system-report error. They appear on every start, the AI cannot fix them, and reporting them invited it to try.
5. **Failed tests are visible.** `dev_cycle` ran Gradle with `-q`, which hides test failures (they log at lifecycle level), so a failing test showed only "2 failed". It now runs without `-q` (with `-Dorg.gradle.welcome=never`) and the condenser drops Gradle chatter, JDK `Unsafe`/agent warnings and MockBukkit frames instead.
6. **World tools fail at once without a server.** With no server started by the core and no `MSC_CORE_ADDRESS`, an agent cannot arrive, so world and build tools answer `the server is not running` immediately instead of after a 30 second wait.
7. **Start reports where the ground is.** `server_start` and `dev_cycle` add `world <name>: top block at 0,0 is y=N (build from y=N+1)` (one heightmap read). Dev worlds are superflat with the surface at y=-61, and builds at y=64 floated in the air.

## Checked

- Full build on Java 25, 55 msc-core tests.
- Cloud, real Paper 26.3 build 249 through MCP stdio from the installed distribution: scaffold into a folder with `.mcp.json` and `.claude`, `server_setup` (agent from `agents/`), `dev_cycle` with a compile error (one-line error), with failing MockBukkit tests (test names and project frames shown), then clean (`no errors or warnings`), `world_heightmap`, `build_structure`, `world_render`, `texture_generate`, `pack_build`, `build_undo`, `server_stop`.
- PC (Windows 11, PowerShell, Claude Code): `installMsc`, the printed `claude mcp add` line pasted as is, `msc_status`, `project_scaffold`, `server_setup` with the bundled agent (tested by the new-user test thread).

## Consequences

- Updating is `git pull` plus `installMsc` again, with Claude Code closed on Windows (the running core locks its jars).
- With `--scope user` every folder Claude Code starts in gets a `.msc/tool-calls.jsonl` once a tool is called.
- Left for later: `world_heightmap` prints raw numbers when the height range is over 35, which is large for mixed terrain (about 4,400 characters for 33 x 33).
