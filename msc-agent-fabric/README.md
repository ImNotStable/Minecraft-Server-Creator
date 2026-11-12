# msc-agent-fabric

A server-side Fabric mod that runs inside the dev server and carries out the core's world and server operations, the same way `msc-agent-paper` does on Paper. It also runs on Quilt, which loads Fabric mods ([ADR 0027](../msc-docs/docs/adr/0027-more-platforms.md)).

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
| `MscAgentMod` | Entry point: forwards Fabric API's server events to the shared `VanillaAgent`, and names the loader (`fabric`, or `quilt` on Quilt) |

Everything that touches Minecraft (worlds, server access, block states, the tick-driven main thread) is the shared [msc-agent-vanilla](../msc-agent-vanilla/README.md) source folder, compiled into this jar and tested here. Operations, sliced jobs, undo and the core connection live in [msc-agent-common](../msc-agent-common/README.md).

## Tests

`./gradlew :msc-agent-fabric:test` runs its own tests and msc-agent-vanilla's inside Fabric Loader (`fabric-loader-junit`) with Minecraft's registries loaded: the shared operations on real block states, the tick-driven main thread, server ops against a fake server, and a smoke test of the whole agent against a real `CoreServer` socket.

## Depends on

- `msc-agent-common` (brings `msc-protocol`)
- Minecraft 26.3 server, Fabric Loader, Fabric API (lifecycle events, networking).
