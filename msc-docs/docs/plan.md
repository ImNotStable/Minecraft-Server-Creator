# Minecraft Server Creator: MVP and post-MVP plan

Source of truth while planning: [shared plan doc](https://claude.ai/artifact/DLkdACibUcy9G29V9cjmUe). This file mirrors it as of 2026-11-10.

## Goal and success metrics

Minecraft Server Creator is a toolset an AI agent uses to develop custom Minecraft servers on any platform (Paper, Fabric, Minestom and others), so the work comes out better, faster and cheaper in tokens than an agent working without it. Beyond writing code, the agent must be able to build in the world, understand the 3D world it sees, and generate textures. Stack: Java 25, Gradle Kotlin DSL.

| Area | What we measure | How |
| --- | --- | --- |
| Quality | Tasks finished with a passing build and smoke test, first try | Benchmark task set, with and without the toolset |
| Speed | Wall-clock time and agent turns per task | Same benchmark run |
| Token usage | Tokens per task, and tokens per tool result | Token counts logged by the core per tool call |

Targets, set from the first full run (2026-11-12: 8 Paper tasks × 3 repeats per mode, claude-sonnet-4-6; [ADR 0004](adr/0004-bench.md)). The toolset is measured against the baseline in the same run:

| Area | First full run (baseline → toolset) | Target |
| --- | --- | --- |
| Quality | 24/24 → 24/24 passing (22/24 as scored; the 2 misses were a bench false negative, fixed) | Toolset passes every task the baseline passes, and the game task `game-sky-sprint` |
| Speed | Median 75 s → 49 s (−35%); median turns 12 → 10 | Median time at least 30% lower, and no task more than 10% slower (now: `world-find-block` +45%, `code-fix-startup` +9%) |
| Tokens | Median 210k → 205k (−2%); cost $8.47 → $4.67 (−45%) | Median tokens at least 20% lower and cost at least 40% lower, and no task more than 10% higher (now: `texture-ruby-item` +69%, `build-hut` +35%) |
| Tool results | `world_slice` 469 tokens per call, 7,048 of about 8,500 tool-result tokens | No tool above 300 tokens per call in the bench (roadmap R9) |

The big win is seeing the world: `build-mirror-copy` drops from 549 s and 1.3M tokens to 76 s and 130k. The gaps to close are where a tool replaced a cheap plain-file step (`texture-ruby-item`, `build-hut`) and finding blocks (`world-find-block`).

## Architecture

The AI talks only to `msc-core` over MCP. The core talks to a thin agent inside each running server over `msc-protocol` (local socket). A new platform means one new agent, not a new toolset.

```
AI agent (Claude Code)
        | MCP tool calls
     msc-core  ----------------> msc-renderer (post-MVP)
        | msc-protocol (local socket)
  +-----+--------------+------------------+
msc-agent-paper   msc-agent-fabric   msc-agent-minestom
   (MVP)            (post-MVP)          (post-MVP)
        \______ msc-agent-common (shared) ______/
```

## MVP scope (Paper, latest stable release)

1. Spec and protocol: tool catalogue with token budgets, core-to-agent protocol, Gradle KTS builds, CLAUDE.md conventions.
2. Server lifecycle: scaffold, download, start, build, deploy, hot-restart in one call; condensed logs; local API index.
3. Build in the world: set, fill, replace, copy/paste, undo, `.schem`/`.nbt` import and export, compact text structure format.
4. See the world (text views): layer slices as character grids, heightmaps, region summaries, all with size budgets.
5. Textures, basic (pixel art only, no image model): 16x16 palette grids to PNG, palette checks, resource pack build and serve.
6. Verify and measure: MockBukkit tests, RCON smoke test, first benchmark of 5 to 10 tasks.

## Post-MVP roadmap

1. Fabric agent.
2. Minestom agent.
3. Rendered views: top-down, isometric, first-person.
4. Deeper world understanding: rooms, walls, paths, structures, snapshot diffs.
5. Advanced textures: image-model option, models, animation, pack-wide checks.
6. Full benchmark in CI and distribution for other users.

## Modules

One monorepo; each module is a folder, later a Gradle subproject.

| Module | Phase | Holds |
| --- | --- | --- |
| msc-docs | MVP | The home for plans, the spec, the tool catalogue and decision records for Minecraft Server Creator. |
| msc-protocol | MVP | The shared message types and local socket transport between `msc-core` and the in-server agents, published as one versioned library. |
| msc-core | MVP | The MCP server the AI talks to. It runs servers, condenses their output, answers API questions, and drives building, world views and textures through the agents. |
| msc-agent-paper | MVP | A Paper plugin that runs inside the dev server and carries out the core's world and server commands. |
| msc-templates | MVP | Starter server projects per platform, ready for the AI to build on, with tests and conventions wired in. |
| msc-bench | MVP | Benchmark tasks and a runner that measure whether the toolset makes AI server development better on quality, speed and tokens. |
| msc-agent-fabric | Post-MVP | A server-side Fabric mod that speaks `msc-protocol`, giving Fabric servers the same world and server tools as Paper. |
| msc-agent-minestom | Post-MVP | A Minestom extension or library hook that speaks `msc-protocol`, giving Minestom servers the same world and server tools. |
| msc-agent-neoforge | Post-MVP | A server-side NeoForge mod that speaks `msc-protocol` ([ADR 0027](adr/0027-more-platforms.md)). |
| msc-agent-forge | Post-MVP | A server-side Minecraft Forge mod that speaks `msc-protocol` ([ADR 0027](adr/0027-more-platforms.md)). |
| msc-agent-vanilla | Post-MVP | Not a Gradle module: the vanilla Minecraft sources the Fabric, NeoForge and Forge agents compile in ([ADR 0027](adr/0027-more-platforms.md)). |
| msc-agent-common | Post-MVP | The platform-neutral half of every agent: operations, sliced jobs, undo and the link to msc-core. Each agent only adapts its server API to it. |
| msc-renderer | Post-MVP (library done; `world_render` tool in msc-core) | A headless renderer that turns a region into images so the AI can check how a build looks. |
| msc-textures | Post-MVP | Advanced texture and model generation beyond the MVP's palette-grid textures. |
| msc-bot | Post-MVP | A headless test player that joins the dev server as a real client, for `player_test`. |
| msc-process | Post-MVP | The server process control msc-core, msc-bench and msc-bot share: the process tree kill, which kills a command with every process it can prove descends from it and never one that only records its pid as a parent, the one RCON client, the one `server.properties` reader and writer with the server address read from it (`ServerAddress`), the one HTTP stall rule (`HttpStall`, which msc-bot uses for the pack download), the UTF-8 console flags of a server's Java (`ServerJava`), and the one atomic file writer. Its own module because the bench must not depend on core ([ADR 0004](adr/0004-bench.md), [ADR 0031](adr/0031-process-tree-kill.md), [ADR 0032](adr/0032-one-rcon-client.md), [ADR 0033](adr/0033-one-server-properties-file.md), [ADR 0034](adr/0034-one-atomic-file-writer.md)). |
| msc-plugin | Post-MVP | The Claude Code plugin: MCP config plus a launcher that runs the newest installed msc-core ([ADR 0021](adr/0021-plugin-install.md)). |

## Decisions

Product decisions were made by Jeremiah on 2026-11-10. Build and design decisions were made by Claude on 2026-11-10, after Jeremiah handed over day-to-day decisions; each links to its ADR where there is one.

| Area | Decision | Record |
| --- | --- | --- |
| First platform | Paper | product |
| Minecraft version | Latest stable release: 26.3 (Paper 26.3 stable since 2026-10-11) | product |
| Repo layout | One monorepo, `msc-` module names | product |
| Textures in the MVP | AI-drawn pixel art only; image models post-MVP | product |
| Benchmark targets | Measure a baseline first, then set targets; set on 2026-11-12 from the first full run (see Goal and success metrics) | product, [ADR 0004](adr/0004-bench.md) |
| Visibility | Private for now; license chosen when going public | product |
| Java | Java 25 (current LTS, required by Minecraft 26.x); Gradle downloads it if missing | [ADR 0001](adr/0001-build-setup.md) |
| Build | Gradle 9.9.0 wrapper, Kotlin DSL, `build-logic` convention plugins, one version catalog | [ADR 0001](adr/0001-build-setup.md) |
| Paper API version | `26.3.build.+`, Paper's recommended form for the latest 26.3 build | [ADR 0001](adr/0001-build-setup.md) |
| Group and packages | `io.github.imnotstable.msc`, one package root per module | [ADR 0001](adr/0001-build-setup.md) |
| Ownership | Root build files, version catalog, `build-logic` and `msc-protocol` are owned by the foundation thread; module threads stay inside their folder (relaxed for fix agents during the 2026-11 campaign, [ADR 0029](adr/0029-perfection-campaign.md)) | [ADR 0001](adr/0001-build-setup.md) |
| Protocol transport | JSON lines over loopback TCP; the agent connects to the core using `MSC_CORE_ADDRESS` and `MSC_TOKEN` | [ADR 0002](adr/0002-protocol.md) |
| JSON library | Gson, because Paper, Fabric and Minestom all ship it | [ADR 0002](adr/0002-protocol.md) |
| Agent scope | Agents only read and write blocks, run commands and apply packs; all AI-facing shaping happens in the core | [ADR 0002](adr/0002-protocol.md) |
| Block data | Vanilla block-state strings; palette + indices in Y-Z-X order; at most 1,048,576 blocks per call | [ADR 0002](adr/0002-protocol.md) |
| MCP server | Official MCP Java SDK (2.1.0) in `msc-core` | [ADR 0002](adr/0002-protocol.md) |
| Starter templates | msc-templates renders `{{var}}` templates via `Templates.scaffold`; `paper-plugin` (Gradle KTS, Brigadier command, MockBukkit test, CLAUDE.md, dev server in `run/`) and `paper-server` (offline, loopback, RCON, flat world) | [ADR 0003](adr/0003-templates.md) |
| Core tools | Few coarse MCP tools (30 at first, 26 since [ADR 0023](adr/0023-texture-tools.md)) with text results capped by a size budget; one text structure format for viewing and building; API index read from class files; tool catalogue in [tools.md](tools.md) | [ADR 0005](adr/0005-core-tools.md) |
| Paper agent | Implements exactly the `Ops` catalogue; main-thread work in 20 ms slices per tick; physics off for fills; in-memory undo of about 8 full-size edits, where undo returns a new id (redo); one shaded plugin jar | [ADR 0006](adr/0006-paper-agent.md) |
| Advanced textures | No image model; colour ramps, seeded patterns, item templates and pack checks in a plain library | [ADR 0007](adr/0007-textures.md) |
| Renderer | Pure-Java software ray caster; own colour table, resource-pack textures when present; 512 px default image budget | [ADR 0008](adr/0008-renderer.md) |
| Fabric agent and template | Minecraft 26.3 with Fabric Loader 0.20.1, Fabric API 0.166.0+26.3 and Loom 1.19.4 (no remapping); same protocol behaviour as the Paper agent; Paper-style world names; one server-only mod jar with msc-protocol, msc-agent-common (since [ADR 0024](adr/0024-agent-common.md)) and three Fabric API modules nested; its Minecraft side is shared with NeoForge and Forge in `msc-agent-vanilla` since [ADR 0027](adr/0027-more-platforms.md); `fabric-mod` template; Gradle daemon on Java 25 | [ADR 0009](adr/0009-fabric-agent.md) |
| Minestom agent | A library the server calls (`MscAgent.start()`), shipped as one shaded jar inside the `minestom-server` template; same ops, limits, undo and events as the Paper agent; instances named by tag; Minestom 2026.10.07 (MC 26.2), no EULA step | [ADR 0010](adr/0010-minestom-agent.md) |
| Core platforms | Core lifecycle tools cover Paper, Fabric and Minestom (and Purpur, Quilt, NeoForge and Forge since [ADR 0027](adr/0027-more-platforms.md)); the project's platform is kept in `.msc/platform` | [ADR 0011](adr/0011-core-platforms.md) |
| CI/CD | GitHub Actions: `./gradlew build` on Java 25 for pushes and PRs (docs-only changes skipped, cache written only by `main`); weekly grouped Dependabot for Gradle and Actions; tag `vX.Y.Z` publishes a GitHub Release with the msc-core zip and the five agent jars (Paper, Fabric, NeoForge, Forge, Minestom); nothing in CI accepts the EULA | [ADR 0012](adr/0012-ci-cd.md) |
| CI end to end | A CI job on Linux and Windows runs `:msc-core:e2eTest` (a real Minestom server scaffolded, built, built in, rendered and play-tested through the installed core over MCP stdio) and `verifyTemplates`; Minestom needs no EULA | [ADR 0022](adr/0022-minestom-e2e-in-ci.md) |
| Install | `:msc-core:installMsc` installs core plus agents under `~/.msc/core`; first-use fixes from dogfooding. The fixed `claude mcp add` registration is superseded by the plugin install (next row, [ADR 0021](adr/0021-plugin-install.md)): each install is a new versioned folder | [ADR 0013](adr/0013-install-and-first-use.md) |
| Plugin install | MSC ships as the `msc` Claude Code plugin: an MCP config plus a one-file Java launcher that starts the newest install under `~/.msc/core`; every `installMsc` is a new folder, so updates need no re-registration and no closed Claude Code | [ADR 0021](adr/0021-plugin-install.md) |
| Detach | `server_detach` leaves the dev server and a standalone pack host running after the core exits; the next session stops them | [ADR 0014](adr/0014-detach.md) |
| Build language | `build_batch`: one call runs fills, rooms, round shapes, lines, terrain bands and stamped structures with weighted block mixes, checks every block name first and keeps one undo id | [ADR 0015](adr/0015-build-batch.md) |
| Test player | `player_test`: an MCProtocolLib client in `msc-bot` joins the dev server, moves with a copy of vanilla player physics, reads blocks through the agent, and reports chat, damage, deaths and server corrections; speaks the previous Minecraft version too (Minestom) through a second MCProtocolLib in its own class loader | [ADR 0016](adr/0016-test-player.md) |
| Workspace and pack lifetime | `msc_workspace path=<dir>` switches the workspace in place (the old one closes as a session end would); the pack port is kept per workspace in `.msc/pack-port` (or `MSC_PACK_PORT`), so the pack URL stays the same; `server_detach` writes the pack lines into `server.properties` and the next start serves that pack again or clears them | [ADR 0017](adr/0017-workspace-and-pack-lifetime.md) |
| Playtest tools | `world_entities` (new `world.entities` op), `server_wait until=<regex>` on the log, `server_setup mode=game`; chunk tickets and `tick sprint` documented | [ADR 0018](adr/0018-playtest-tools.md) |
| Smaller tool surface | `build_batch` is the one build tool (new `copy` step; `build_fill`, `build_set`, `build_structure`, `build_copy`, `structure_load` removed), `msc_stats` folded into `msc_status stats=true`, shorter instructions and results: 36 to 30 tools, 18.5k to 15.75k chars | [ADR 0019](adr/0019-smaller-tool-surface.md) |
| EULA per machine | The owner's acceptance is kept once per machine in `~/.msc/eula.json`, which `server_setup` and `server_start` read to write `eula=true`. Only three explicit opt-ins by the owner write it: a typed `yes` to `Main eula accept` in an interactive terminal, `installMsc -Pmsc.acceptEula=true`, or the first `acceptEula=true` on `server_setup` or `server_start` (passed only after the user's own yes). Installing or using MSC alone never accepts the EULA, and nothing writes the file by default (amended 2026-11-12 and 2026-11-14) | [ADR 0020](adr/0020-machine-eula.md) |
| Texture tools | `texture` draws, generates or recolors, and animates; `pack` builds and lists problems (`check=true` only lints); `model_create` and `texture_view` stay. New `sword`, `pickaxe` and `food` shapes. 30 to 26 tools | [ADR 0023](adr/0023-texture-tools.md) |
| Shared agent code | `msc-agent-common` holds the operations, jobs, undo and core link once, generic over the block state type; Paper, Fabric and Minestom keep only adapters (`AgentWorlds`, `AgentWorld`, `AgentServer`, `MainThread`) | [ADR 0024](adr/0024-agent-common.md) |
| CI cost | Pull requests run only the Ubuntu build; Windows and the Minestom e2e job run on main, nightly, by hand, or on a `full-ci` label | [ADR 0025](adr/0025-ci-cost.md) |
| Generated tool docs | `tools.md` tool tables come from the tool definitions (`./gradlew :msc-core:toolDocs`); `checkToolDocs` in `build` fails when they or the wiki tool page are stale | [ADR 0026](adr/0026-generated-tool-docs.md) |
| More platforms | NeoForge, Forge, Quilt and Purpur join Paper, Fabric and Minestom. The Minecraft side of the mod agents is one shared source folder (`msc-agent-vanilla`); new `msc-agent-neoforge` and `msc-agent-forge`; Quilt runs the Fabric agent, Purpur the Paper agent; `server_setup` runs each loader's installer; `neoforge-mod` and `forge-mod` templates. Folia and proxies left out | [ADR 0027](adr/0027-more-platforms.md) |
| First-build warmup | `project_scaffold` starts the first build of `neoforge-mod` and `forge-mod` in the background (`warmup=true` in `template.properties`); `dev_cycle` waits for it. Forge/NeoForge log lines parsed without the logger tag; OSHI noise and `Transformer Audit` dropped | [ADR 0028](adr/0028-first-build-warmup.md) |
| Perfection campaign | Two days, 2026-11-12 to 14: 14 pieces audited until two clean rounds in a row, findings verified before they are filed as GitHub issues, rolling fixes per piece, a seven-platform matrix on the PC before and after. Plan and state in [campaign-2026-11.md](campaign-2026-11.md) | [ADR 0029](adr/0029-perfection-campaign.md) |
| Protocol result encoding | `Response.result` and `Request.params` are `RawJson` (JSON text): results and params go from record to frame and back with no `JsonElement` tree; the wire format is unchanged. Full-region `world.read` round trip about 400 ms to about 200 ms; full-region `world.write` (#360) about 600 ms to about 40 ms | [ADR 0030](adr/0030-protocol-result-encoding.md) |
| Process tree kill | One tree kill for msc-core and msc-bench in a new module, `msc-process`. A process is signalled only when it is proven to descend from the root: its parent is in the same snapshot with its own start instant and started no later than it. Every signal goes by identity. Unix freezes the tree, then kills it. Windows kills from the leaves up, the root last. A Windows child that cannot be proven is left running. The Windows branch is checked only against a fake table until a run on the PC | [ADR 0031](adr/0031-process-tree-kill.md) |
| One RCON client | The bench's RCON client moves to `msc-process` (package `process.rcon`) and is the only one; msc-core's `Rcon` is gone and `RconTarget` uses it to stop a leftover server. UTF-8 everywhere; a command or password over 1446 bytes (one vanilla read of 1460 minus 14 of framing) is refused before sending; timeouts must be positive. `FakeRconServer` is shared through `msc-process` test fixtures | [ADR 0032](adr/0032-one-rcon-client.md) |
| One server.properties reader and writer | `ServerPropertiesFile` in `msc-process` is the only code that reads or writes a server's `server.properties`, for msc-core and msc-bench. It reads as the 26.3 server does (strict UTF-8, then ISO-8859-1, `Properties.load` rules) and writes keys and values escaped as `Properties.store` escapes them, non-ASCII as `\uXXXX`, so the server reads back exactly what was set; other lines, comments and order stay. msc-core's `repoChecks` fails on a hand-rolled reader or writer | [ADR 0033](adr/0033-one-server-properties-file.md) |
| One atomic file writer | `AtomicFile` (temp file beside the target, moved into place, permissions of the replaced file kept, never `0600`) moves from msc-core to `msc-process`, so `ServerPropertiesFile.set` writes whole too and the repository has one temp-then-move writer | [ADR 0034](adr/0034-one-atomic-file-writer.md) |
| Guard tests are tripwires | The source-scanning tests in `repoChecks` (repo inputs, EULA writers, server.properties access) never fail on healthy code, catch every ordinary way of doing the guarded thing with the calls this codebase uses, keep tables of what they must and must not flag, and say what to do. Reflection, names built at run time and other deliberate evasion are out of scope. Every ADR has a row or mention in this plan | [ADR 0035](adr/0035-guard-tests-are-tripwires.md) |
| Guards by construction and behaviour | An invariant is kept first by construction (the EULA file's path never leaves `MachineEula`, which `CoreConfig` holds as an object), then by a behaviour test (every tool and command runs against a temporary home and must not accept the EULA without an opt-in), and a source guard is only a plain literal or token rule with no flow tracking (`eula.json`, `server.properties`); amends ADR 0035 | [ADR 0036](adr/0036-guards-by-construction-and-behaviour.md) |
| A server belongs to its live core | `.msc/server.pid` also names the core that started the server (pid and start time); a server is a leftover only when that core is not alive (an old-format file: owner unknown, a leftover as before). While another live core's server runs, `server_start`, `server_stop`, `dev_cycle` and `server_setup` refuse naming that core's pid and change nothing, and `msc_status` says so; a detached server keeps its owner until that session ends. Owner fields, not a workspace lock; every read-decide-write of the pid file is one short critical section across cores (a lock on `.msc/server.lock` around check, launch and record, not held for a server's life, #490); amends ADR 0013 point 8 and ADR 0014 | [ADR 0037](adr/0037-server-belongs-to-its-live-core.md) |
| Cloud session caches | A SessionStart hook (`.claude/hooks/session-start.sh`, cloud sessions only) installs Corretto 25, points Gradle at Google's Maven Central mirror and warms every dependency and Minecraft setup once per change of the build files; msc-core keeps checksummed server jars in a machine-wide store (`~/.msc/downloads`, `MSC_DOWNLOAD_STORE`) shared by every workspace | [ADR 0038](adr/0038-cloud-session-caches.md) |
| Upstream version check | `./gradlew checkUpstreamVersions` prints one line per pinned component (Minecraft, Paper, Fabric Loader and API, NeoForge, Forge, Quilt, Minestom): pinned, newest stable upstream, `NEWER` / `up to date` / `could not check`. Run by hand, never part of `build`, fails soft offline; no tool change (the AI cannot act on a newer Minecraft, and `server_setup` already takes Paper's newest stable build); no scheduled workflow | [ADR 0039](adr/0039-upstream-version-check.md) |
| Crash triage | `server_logs mode=triage` (no new tool): at most 3 findings, each what died, the culprit (first frame outside JDK, server and loader packages), the root cause, up to 3 key frames and a one-line fix, from the log, the newest crash report and `hs_err_pid*.log` since the last start; known causes (EULA, port, Java version, out of memory, missing dependencies, plugin and mod load failures, mixins, watchdog) matched per platform; `server_start` and `dev_cycle` show it first when the server exits | [ADR 0040](adr/0040-crash-triage.md) |
| World backups | `build_undo backup=` lists, saves or restores whole-world backups: folder copies of every world folder (`level.dat` or `region/`) in `.msc/backups`, newest 5 within 2 GiB, flushed with `save-off`/`save-all flush`/`save-on`; a build of 100k+ blocks takes one first (at most every 5 minutes); rollback stops the server, saves the current world, swaps the folders by rename and starts it again. Minestom saves only on stop. No new tool, no protocol change | [ADR 0041](adr/0041-world-backups.md) |
| Block entity data | NBT audit: entities and items stay with `server_command` (`data`, `summon`, `give`) on the vanilla platforms; Minestom has none to edit. Block entity data rides on palette entries as SNBT (`chest{Items:[...]}`): `build_batch` B{SNBT} merges it as `/data merge block` does with one undo, `world_slice data=true` shows it, `copy`, `structure_save` and `.schem` steps keep it, on all seven platforms. One optional `includeBlockData` read flag in the protocol | [ADR 0042](adr/0042-block-entity-data.md) |
