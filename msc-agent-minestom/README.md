# msc-agent-minestom

A library a Minestom server calls at startup so msc-core's world and server tools work on it, the same way `msc-agent-paper` does for Paper.

**Phase:** Post-MVP  
**Status:** Built and tested (28 tests, plus an end-to-end run of the template's server jar against a protocol core). Design decisions: [ADR 0010](../msc-docs/docs/adr/0010-minestom-agent.md).

## Use

```java
MinecraftServer server = MinecraftServer.init();
InstanceContainer world = MinecraftServer.getInstanceManager().createInstanceContainer();
MscAgent.name(world, "world");   // the name the AI's tools use; unnamed instances go by UUID
MscAgent.start();                // idle unless msc-core started this server
server.start("127.0.0.1", 25565);
```

The `minestom-server` template in `msc-templates` already does this and ships the jar as `libs/msc-agent-minestom.jar`.

## What it does

- Connects to `msc-core` over `msc-protocol` (`MSC_CORE_ADDRESS`, `MSC_TOKEN`; or `-Dmsc.core.address=host:port -Dmsc.token=...`), retrying and reconnecting like the Paper agent.
- Implements every operation in `Ops`, with the Paper agent's limits, errors, undo and events.
- Runs world work on Minestom's scheduler at tick start in 20 ms slices; loads or generates the chunks a call touches first.
- `server.command` runs the server's own commands (Minestom has no vanilla ones).

## Build

```
./gradlew :msc-agent-minestom:build
```

The jar is `build/libs/msc-agent-minestom-<version>.jar` (msc-protocol shaded in; Gson comes from Minestom).

## Code map

| Class | Role |
| --- | --- |
| `MscAgent` | Public entry point: start, name instances; wires the shared operations and core link to Minestom events |
| `Worlds`, `MinestomWorld`, `BlockStates`, `SignNbt` | World names; one instance's blocks (with chunk loading), entities and signs; block state strings |
| `MinestomServer`, `CapturingSender`, `TickRate`, `MinestomServerFacts` | Info, commands, resource packs, TPS |
| `SchedulerMainThread` | Runs work in per-tick slices |

Operations, sliced jobs, undo and the core connection live in [msc-agent-common](../msc-agent-common/README.md); this module only adapts the server API to them.

## Depends on

- `msc-agent-common` (brings `msc-protocol`)
- Minestom `2026.10.07-26.2` (compileOnly; Minecraft 26.2), `net.minestom:testing` for tests.
