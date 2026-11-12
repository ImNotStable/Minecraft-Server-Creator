# msc-agent-vanilla

The Minecraft side every mod-loader agent shares: worlds, server access, block states and the tick-driven main thread, written against vanilla Minecraft classes only. Minecraft 26.x is unobfuscated, so the same sources compile unchanged on Fabric, Quilt, NeoForge and Forge. Design: [ADR 0027](../msc-docs/docs/adr/0027-more-platforms.md).

This is a **source folder, not a Gradle project**. `msc-agent-fabric`, `msc-agent-neoforge` and `msc-agent-forge` each add `src/main/java` to their own source set and compile it against their own Minecraft. The tests in `src/test/java` run in `msc-agent-fabric` (`./gradlew :msc-agent-fabric:test`), where Fabric Loader bootstraps Minecraft.

| Class | Role |
| --- | --- |
| `VanillaAgent` | The agent's life on one server; loader entry points forward `starting`, `started`, `stopping`, `tick`, `joined`, `quit` |
| `Loader` | The platform id and loader version the agent reports |
| `VanillaLevels`, `VanillaLevel`, `LevelNames` | Worlds by Paper-style name; one world's blocks, entities and signs |
| `VanillaServer` | TPS, commands, packs on the real server |
| `BlockStates` | Block state strings to states and back |
| `TickMainThread` | Runs work on the server thread in per-tick slices |

Operations, sliced jobs, undo and the core connection live in [msc-agent-common](../msc-agent-common/README.md).
