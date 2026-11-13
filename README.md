# Minecraft Server Creator

A toolset that helps an AI develop custom Minecraft servers on any platform. This is one git repo (a monorepo); each folder is a module. It targets the latest stable Minecraft release on Paper, Purpur, Fabric, Quilt, NeoForge, Forge and Minestom ([Platforms](docs/wiki/Platforms.md)).

## Quick start (Claude Code)

1. Install msc-core (needs nothing but this repo; Gradle fetches Java 25 if missing):

   ```
   ./gradlew :msc-core:installMsc        # Windows: gradlew.bat :msc-core:installMsc
   ```

   Each run puts msc-core and its Paper, Fabric, NeoForge and Forge agents in a new folder under `~/.msc/core`.
2. Add the `msc` plugin to Claude Code once (it needs `java` on PATH, any version from 17):

   ```
   /plugin marketplace add <this repo's folder>      (or: /plugin marketplace add ImNotStable/Minecraft-Server-Creator)
   /plugin install msc@msc
   ```

   The plugin's launcher starts the newest install every time, so you never re-register MSC after an update. Coming from an older install? Remove the old registration first: `claude mcp remove msc -s user`.
3. Make an empty folder, start `claude` in it and ask for a server, for example: "Make a Paper lobby plugin with a /spawn command and build a small spawn platform." The AI scaffolds the project there (`project_scaffold`), downloads Paper and installs the agent (`server_setup`), then builds, deploys and restarts in one call (`dev_cycle`).

Every dev server except Minestom (Paper, Purpur, Fabric, Quilt, NeoForge, Forge) needs your agreement to the Minecraft EULA (https://aka.ms/MinecraftEULA). Nothing accepts it by default. To turn on automatic acceptance for your machine, install with `./gradlew :msc-core:installMsc -Pmsc.acceptEula=true`, or say yes once when the AI asks (it passes `acceptEula=true`). Either one confirms that you read and accept Mojang's EULA, as set out in [TERMS.md](TERMS.md), and from then on MSC writes `eula=true` for every such dev server on that machine ([ADR 0020](msc-docs/docs/adr/0020-machine-eula.md)).

Step-by-step guide: [Wiki](https://github.com/ImNotStable/Minecraft-Server-Creator/wiki) (source in `docs/wiki/`).

To update after pulling new code, run `installMsc` again, then reconnect msc in `/mcp` (or restart Claude Code). Claude Code can stay open: each install is a new folder. Tool reference: [msc-docs/docs/tools.md](msc-docs/docs/tools.md).

| Module | What it is |
| --- | --- |
| [msc-docs](msc-docs/README.md) | Plan, tool catalogue, protocol and decision records |
| [msc-protocol](msc-protocol/README.md) | Messages and transport between the core and the agents |
| [msc-core](msc-core/README.md) | The MCP server the AI talks to |
| [msc-agent-common](msc-agent-common/README.md) | The platform-neutral half of every agent |
| [msc-agent-paper](msc-agent-paper/README.md) | Agent for Paper and Purpur |
| [msc-agent-fabric](msc-agent-fabric/README.md) | Agent for Fabric and Quilt |
| [msc-agent-neoforge](msc-agent-neoforge/README.md) | Agent for NeoForge |
| [msc-agent-forge](msc-agent-forge/README.md) | Agent for Forge |
| [msc-agent-vanilla](msc-agent-vanilla/README.md) | Vanilla Minecraft sources the Fabric, NeoForge and Forge agents compile in (not a Gradle module) |
| [msc-agent-minestom](msc-agent-minestom/README.md) | Agent for Minestom |
| [msc-templates](msc-templates/README.md) | Starter projects per platform |
| [msc-renderer](msc-renderer/README.md) | Headless renderer behind `world_render` |
| [msc-textures](msc-textures/README.md) | Texture and resource pack generation |
| [msc-bot](msc-bot/) | Headless test player behind `player_test` |
| [msc-bench](msc-bench/README.md) | Benchmark tasks and runner |
| [msc-process](msc-process/README.md) | Process tree kill shared by msc-core and msc-bench |
| [msc-plugin](msc-plugin/README.md) | The Claude Code plugin and its launcher |

## Build (developing this repo)

Java 25 and Gradle 9.9 (wrapper). Gradle downloads Java 25 if it is not installed.

```
./gradlew build
```

Working conventions for humans and AI are in [CLAUDE.md](CLAUDE.md).

The full plan is in [msc-docs/docs/plan.md](msc-docs/docs/plan.md).
