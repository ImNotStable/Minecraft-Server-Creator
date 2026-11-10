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
| msc-renderer | Post-MVP | A headless renderer that turns a region into images so the AI can check how a build looks. |
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
