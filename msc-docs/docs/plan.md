# Minecraft Server Creator: MVP and post-MVP plan

Source of truth while planning: [shared plan doc](https://claude.ai/artifact/DLkdACibUcy9G29V9cjmUe). This file mirrors it as of 2026-11-10.

## Goal and success metrics

Minecraft Server Creator is a toolset an AI agent uses to develop custom Minecraft servers on any platform (Paper, Fabric, Minestom and others), so the work comes out better, faster and cheaper in tokens than an agent working without it. Beyond writing code, the agent must be able to build in the world, understand the 3D world it sees, and generate textures. Stack: Java 25, Gradle Kotlin DSL.

| Area | What we measure | How |
| --- | --- | --- |
| Quality | Tasks finished with a passing build and smoke test, first try | Benchmark task set, with and without the toolset |
| Speed | Wall-clock time and agent turns per task | Same benchmark run |
| Token usage | Tokens per task, and tokens per tool result | Token counts logged by the core per tool call |

Targets are set after a baseline run without the toolset.

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
| msc-agent-common | Post-MVP | The platform-neutral half of every agent: operations, sliced jobs, undo and the link to msc-core. Each agent only adapts its server API to it. |
| msc-renderer | Post-MVP (library done; `world_render` tool in msc-core) | A headless renderer that turns a region into images so the AI can check how a build looks. |
| msc-textures | Post-MVP | Advanced texture and model generation beyond the MVP's palette-grid textures. |

## Decisions

Product decisions were made by Jeremiah on 2026-11-10. Build and design decisions were made by Claude on 2026-11-10, after Jeremiah handed over day-to-day decisions; each links to its ADR where there is one.

| Area | Decision | Record |
| --- | --- | --- |
| First platform | Paper | product |
| Minecraft version | Latest stable release: 26.3 (Paper 26.3 stable since 2026-10-11) | product |
| Repo layout | One monorepo, `msc-` module names | product |
| Textures in the MVP | AI-drawn pixel art only; image models post-MVP | product |
| Benchmark targets | Measure a baseline first, then set targets | product |
| Visibility | Private for now; license chosen when going public | product |
| Java | Java 25 (current LTS, required by Minecraft 26.x); Gradle downloads it if missing | [ADR 0001](adr/0001-build-setup.md) |
| Build | Gradle 9.9.0 wrapper, Kotlin DSL, `build-logic` convention plugins, one version catalog | [ADR 0001](adr/0001-build-setup.md) |
| Paper API version | `26.3.build.+`, Paper's recommended form for the latest 26.3 build | [ADR 0001](adr/0001-build-setup.md) |
| Group and packages | `io.github.imnotstable.msc`, one package root per module | [ADR 0001](adr/0001-build-setup.md) |
| Ownership | Root build files, version catalog, `build-logic` and `msc-protocol` are owned by the foundation thread; module threads stay inside their folder | [ADR 0001](adr/0001-build-setup.md) |
| Protocol transport | JSON lines over loopback TCP; the agent connects to the core using `MSC_CORE_ADDRESS` and `MSC_TOKEN` | [ADR 0002](adr/0002-protocol.md) |
| JSON library | Gson, because Paper, Fabric and Minestom all ship it | [ADR 0002](adr/0002-protocol.md) |
| Agent scope | Agents only read and write blocks, run commands and apply packs; all AI-facing shaping happens in the core | [ADR 0002](adr/0002-protocol.md) |
| Block data | Vanilla block-state strings; palette + indices in Y-Z-X order; at most 1,048,576 blocks per call | [ADR 0002](adr/0002-protocol.md) |
| MCP server | Official MCP Java SDK (2.1.0) in `msc-core` | [ADR 0002](adr/0002-protocol.md) |
| Starter templates | msc-templates renders `{{var}}` templates via `Templates.scaffold`; `paper-plugin` (Gradle KTS, Brigadier command, MockBukkit test, CLAUDE.md, dev server in `run/`) and `paper-server` (offline, loopback, RCON, flat world) | [ADR 0003](adr/0003-templates.md) |
| Core tools | 30 coarse MCP tools with text results capped by a size budget; one text structure format for viewing and building; API index read from class files; tool catalogue in [tools.md](tools.md) | [ADR 0005](adr/0005-core-tools.md) |
| Paper agent | Implements exactly the `Ops` catalogue; main-thread work in 20 ms slices per tick; physics off for fills; in-memory undo of about 8 full-size edits, where undo returns a new id (redo); one shaded plugin jar | [ADR 0006](adr/0006-paper-agent.md) |
| Advanced textures | No image model; colour ramps, seeded patterns, item templates and pack checks in a plain library | [ADR 0007](adr/0007-textures.md) |
| Renderer | Pure-Java software ray caster; own colour table, resource-pack textures when present; 512 px default image budget | [ADR 0008](adr/0008-renderer.md) |
| Fabric agent and template | Minecraft 26.3 with Fabric Loader 0.20.1, Fabric API 0.166.0+26.3 and Loom 1.19.4 (no remapping); same protocol behaviour as the Paper agent; Paper-style world names; one server-only mod jar with msc-protocol and three Fabric API modules nested; `fabric-mod` template; Gradle daemon on Java 25 | [ADR 0009](adr/0009-fabric-agent.md) |
| Minestom agent | A library the server calls (`MscAgent.start()`), shipped as one shaded jar inside the `minestom-server` template; same ops, limits, undo and events as the Paper agent; instances named by tag; Minestom 2026.10.07 (MC 26.2), no EULA step | [ADR 0010](adr/0010-minestom-agent.md) |
| Core platforms | Core lifecycle tools cover Paper, Fabric and Minestom; the project's platform is kept in `.msc/platform` | [ADR 0011](adr/0011-core-platforms.md) |
| CI/CD | GitHub Actions: `./gradlew build` on Java 25 for pushes and PRs (docs-only changes skipped, cache written only by `main`); weekly grouped Dependabot for Gradle and Actions; tag `vX.Y.Z` publishes a GitHub Release with the msc-core zip and the three agent jars; nothing in CI accepts the EULA | [ADR 0012](adr/0012-ci-cd.md) |
| CI end to end | A CI job on Linux and Windows runs `:msc-core:e2eTest` (a real Minestom server scaffolded, built, built in, rendered and play-tested through the installed core over MCP stdio) and `verifyTemplates`; Minestom needs no EULA | [ADR 0022](adr/0022-minestom-e2e-in-ci.md) |
| Install | `:msc-core:installMsc` installs core plus agents to `~/.msc/core` and prints the `claude mcp add` line; first-use fixes from dogfooding | [ADR 0013](adr/0013-install-and-first-use.md) |
| Plugin install | MSC ships as the `msc` Claude Code plugin: an MCP config plus a one-file Java launcher that starts the newest install under `~/.msc/core`; every `installMsc` is a new folder, so updates need no re-registration and no closed Claude Code | [ADR 0021](adr/0021-plugin-install.md) |
| Detach | `server_detach` leaves the dev server and a standalone pack host running after the core exits; the next session stops them | [ADR 0014](adr/0014-detach.md) |
| Build language | `build_batch`: one call runs fills, rooms, round shapes, lines, terrain bands and stamped structures with weighted block mixes, checks every block name first and keeps one undo id | [ADR 0015](adr/0015-build-batch.md) |
| Test player | `player_test`: an MCProtocolLib client in `msc-bot` joins the dev server, moves with a copy of vanilla player physics, reads blocks through the agent, and reports chat, damage, deaths and server corrections; speaks the previous Minecraft version too (Minestom) through a second MCProtocolLib in its own class loader | [ADR 0016](adr/0016-test-player.md) |
| Playtest tools | `world_entities` (new `world.entities` op), `server_wait until=<regex>` on the log, `server_setup mode=game`; chunk tickets and `tick sprint` documented | [ADR 0018](adr/0018-playtest-tools.md) |
| Smaller tool surface | `build_batch` is the one build tool (new `copy` step; `build_fill`, `build_set`, `build_structure`, `build_copy`, `structure_load` removed), `msc_stats` folded into `msc_status stats=true`, shorter instructions and results: 36 to 30 tools, 18.5k to 15.75k chars | [ADR 0019](adr/0019-smaller-tool-surface.md) |
| EULA per machine | The owner runs `Main eula accept` once in an interactive terminal; it writes `~/.msc/eula.json`, which `server_setup` and `server_start` read to write `eula=true`. No tool can write it | [ADR 0020](adr/0020-machine-eula.md) |
| Texture tools | `texture` draws, generates or recolors, and animates; `pack` builds and lists problems (`check=true` only lints); `model_create` and `texture_view` stay. New `sword`, `pickaxe` and `food` shapes. 30 to 26 tools | [ADR 0023](adr/0023-texture-tools.md) |
| Shared agent code | `msc-agent-common` holds the operations, jobs, undo and core link once, generic over the block state type; Paper, Fabric and Minestom keep only adapters (`AgentWorlds`, `AgentWorld`, `AgentServer`, `MainThread`) | [ADR 0024](adr/0024-agent-common.md) |
| CI cost | Pull requests run only the Ubuntu build; Windows and the Minestom e2e job run on main, nightly, by hand, or on a `full-ci` label | [ADR 0025](adr/0025-ci-cost.md) |
