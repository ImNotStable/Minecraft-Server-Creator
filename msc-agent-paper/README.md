# msc-agent-paper

A Paper plugin that runs inside the dev server and carries out the core's world and server operations.

**Phase:** MVP  
**Status:** MVP built and unit tested. Design decisions: [ADR 0006](../msc-docs/docs/adr/0006-paper-agent.md).

## What it does

- Connects to `msc-core` over `msc-protocol` when the core starts the server (`MSC_CORE_ADDRESS`, `MSC_TOKEN`; or `-Dmsc.core.address=host:port -Dmsc.token=...`). Without them it stays idle.
- Implements every operation in `Ops`: `server.info`, `server.command`, `world.read`, `world.heightmap`, `world.write`, `world.undo`, `pack.apply`.
- Runs world work on the main thread in 20 ms slices per tick, so a 1M-block edit doesn't stall the server.
- Keeps undo history in memory (about 8 full-size edits); undo returns a new edit id, so undo can be undone.
- Sends `server.ready`, `server.stopping`, `player.join` and `player.quit` events.
- Applies resource packs to online players and to players who join later.

## Build

```
./gradlew :msc-agent-paper:build
```

The plugin jar is `build/libs/msc-agent-paper-<version>.jar` (msc-protocol shaded in).

## Code map

| Class | Role |
| --- | --- |
| `MscAgentPlugin` | Entry point: wires the shared operations and core link to Bukkit events |
| `PaperWorlds`, `PaperWorld` | Worlds by name, block data parsing with close-name hints (`BlockNames` in msc-agent-common); one world's blocks, entities and signs |
| `PaperServer`, `ServerFacts`, `PaperServerFacts` | Versions, TPS, commands, resource packs |
| `BukkitMainThread` | Runs work on the main thread in per-tick slices |

Operations, sliced jobs, undo and the core connection live in [msc-agent-common](../msc-agent-common/README.md); this module only adapts the server API to them.

## Depends on

- `msc-agent-common` (brings `msc-protocol`)
- Paper API 26.3 (compileOnly), MockBukkit for tests.
