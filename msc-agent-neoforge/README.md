# msc-agent-neoforge

A server-side NeoForge mod that runs inside the dev server and carries out the core's world and server operations, like the Paper and Fabric agents. Design: [ADR 0027](../msc-docs/docs/adr/0027-more-platforms.md).

**Status:** Built for Minecraft 26.3. NeoForge finds it as a valid mod (`msc_agent`) on a real 26.3 server; its operations on a running world still need a check on a machine whose owner accepted the Minecraft EULA.

## Build

```
./gradlew :msc-agent-neoforge:build
```

The mod jar is `build/libs/msc-agent-neoforge-<version>.jar`, with msc-agent-common and msc-protocol shaded in, so a server needs only NeoForge and this jar in `mods/`. `server_setup platform=neoforge` installs both.

## Code map

| Class | Role |
| --- | --- |
| `MscAgentNeoForge` | Entry point: forwards NeoForge's server events to the shared `VanillaAgent` |

Everything else is the shared [msc-agent-vanilla](../msc-agent-vanilla/README.md) source folder (tested in msc-agent-fabric) and [msc-agent-common](../msc-agent-common/README.md).
