# msc-agent-forge

A server-side Minecraft Forge mod that runs inside the dev server and carries out the core's world and server operations, like the Paper and Fabric agents. Design: [ADR 0027](../msc-docs/docs/adr/0027-more-platforms.md).

**Status:** Built for Minecraft 26.3 (compiled against vanilla Minecraft through ModDevGradle's vanilla mode plus Forge's API jars; ForgeGradle needs project repositories, which this build forbids). Minecraft Forge finds it as a valid mod (`msc_agent`) on a real 26.3 server; its operations on a running world still need a check on a machine whose owner accepted the Minecraft EULA.

## Build

```
./gradlew :msc-agent-forge:build
```

The mod jar is `build/libs/msc-agent-forge-<version>.jar`, with msc-agent-common and msc-protocol shaded in, so a server needs only Minecraft Forge and this jar in `mods/`. `server_setup platform=forge` installs both.

## Code map

| Class | Role |
| --- | --- |
| `MscAgentForge` | Entry point: forwards Minecraft Forge's server events to the shared `VanillaAgent` |

Everything else is the shared [msc-agent-vanilla](../msc-agent-vanilla/README.md) source folder (tested in msc-agent-fabric) and [msc-agent-common](../msc-agent-common/README.md).
