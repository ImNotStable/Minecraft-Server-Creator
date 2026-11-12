# msc-agent-fabric

A server-side Fabric mod that runs inside the dev server and carries out the core's world and server operations, the same way `msc-agent-paper` does on Paper.

**Phase:** Post-MVP  
**Status:** Built and unit tested on Minecraft 26.3 (Fabric Loader 0.20.1, Fabric API 0.166.0+26.3). Loads on a real Fabric 26.3 server. Design decisions: [ADR 0009](../msc-docs/docs/adr/0009-fabric-agent.md).

## What it does

- Connects to `msc-core` over `msc-protocol` when the core starts the server (`MSC_CORE_ADDRESS`, `MSC_TOKEN`; or `-Dmsc.core.address=host:port -Dmsc.token=...`). Without them it stays idle.
- Implements every operation in `Ops`: `server.info`, `server.command`, `world.read`, `world.heightmap`, `world.write`, `world.undo`, `pack.apply`. Behaviour matches the Paper agent.
- Names worlds like Paper (`world`, `world_nether`, `world_the_end`; other dimensions by id), and also accepts dimension ids.
- Runs world work on the server thread in 20 ms slices per tick, so a 1M-block edit doesn't stall the server.
- Keeps undo history in memory (about 8 full-size edits); undo returns a new edit id, so undo can be undone.
- Sends `server.ready`, `server.stopping`, `player.join` and `player.quit` events.
- Applies resource packs to online players and to players who join later.

## Build

```
./gradlew :msc-agent-fabric:build
```

Gradle must run on Java 25 for Fabric Loom; `gradle/gradle-daemon-jvm.properties` takes care of that. The mod jar is `build/libs/msc-agent-fabric-<version>.jar`. It nests msc-protocol and the Fabric API modules it uses, so a server needs only Fabric Loader and this jar in `mods/`.

## Code map

| Class | Role |
| --- | --- |
| `MscAgentMod` | Entry point: wires everything to Fabric events |
| `AgentLink` | Connection to the core: connect, reconnect, events |
| `AgentRoutes` | Op name to handler table |
| `WorldOps` | Reads, heightmaps, fill/write/setBlocks/undo |
| `ServerOps` | Info, commands, resource packs |
| `AgentLevel`, `FabricLevel`, `Levels`, `FabricLevels`, `LevelNames` | One world's blocks; worlds by name |
| `ServerAccess`, `FabricServerAccess` | Versions, TPS, commands, packs on the real server |
| `BlockStates` | Block state strings to states and back |
| `MainThread`, `TickMainThread`, `SlicedJob` | Running work on the server thread in per-tick slices |
| `EditJob`, `ReadJob`, `HeightmapJob` | The sliced jobs |
| `EditHistory`, `UndoRecord` | Undo |

## Tests

`./gradlew :msc-agent-fabric:test` runs 33 tests inside Fabric Loader (`fabric-loader-junit`) with Minecraft's registries loaded: operations against an in-memory world with real block states, the tick-driven main thread, server ops against a fake server, and a smoke test of the whole agent against a real `CoreServer` socket.

## Depends on

- `msc-protocol`
- Minecraft 26.3 server, Fabric Loader, Fabric API (lifecycle events, networking).
