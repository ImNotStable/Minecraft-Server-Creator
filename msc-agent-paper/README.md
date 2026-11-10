# msc-agent-paper

A Paper plugin that runs inside the dev server and carries out the core's world and server operations.

**Phase:** MVP  
**Status:** MVP built and unit tested. Design decisions: [ADR 0006](../msc-docs/docs/adr/0006-paper-agent.md).

## What it does

- Connects to `msc-core` over `msc-protocol` when the core starts the server (`MSC_CORE_ADDRESS`, `MSC_TOKEN`; or `-Dmsc.core.address=host:port -Dmsc.token=...`). Without them it stays idle.
- Implements every operation in `Ops`: `server.info`, `server.command`, `world.read`, `world.heightmap`, `world.fill`, `world.write`, `world.setBlocks`, `world.undo`, `pack.apply`.
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
| `MscAgentPlugin` | Entry point: connects, routes ops, sends events |
| `AgentRoutes` | Op name to handler table |
| `WorldOps` | Reads, heightmaps, fill/write/setBlocks/undo |
| `ServerOps` | Info, commands, resource packs |
| `MainThread`, `BukkitMainThread`, `SlicedJob` | Running work on the main thread in per-tick slices |
| `EditJob`, `ReadJob`, `HeightmapJob` | The sliced jobs |
| `EditHistory`, `UndoRecord` | Undo |

## Depends on

- `msc-protocol`
- Paper API 26.3 (compileOnly), MockBukkit for tests.
