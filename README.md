# Minecraft Server Creator

A toolset that helps an AI develop custom Minecraft servers on any platform. This is one git repo (a monorepo); each folder is a module. The MVP targets Paper on the latest stable Minecraft release.

## Quick start (Claude Code)

1. Install msc-core (needs nothing but this repo; Gradle fetches Java 25 if missing):

   ```
   ./gradlew :msc-core:installMsc        # Windows: gradlew.bat :msc-core:installMsc
   ```

   Each run puts msc-core and its Paper and Fabric agents in a new folder under `~/.msc/core`.
2. Add the `msc` plugin to Claude Code once (it needs `java` on PATH, any version from 17):

   ```
   /plugin marketplace add <this repo's folder>      (or: /plugin marketplace add ImNotStable/Minecraft-Server-Creator)
   /plugin install msc@msc
   ```

   The plugin's launcher starts the newest install every time, so you never re-register MSC after an update. Coming from an older install? Remove the old registration first: `claude mcp remove msc -s user`.
3. Make an empty folder, start `claude` in it and ask for a server, for example: "Make a Paper lobby plugin with a /spawn command and build a small spawn platform." The AI scaffolds the project there (`project_scaffold`), downloads Paper and installs the agent (`server_setup`), then builds, deploys and restarts in one call (`dev_cycle`).

The dev server's Minecraft EULA (https://aka.ms/MinecraftEULA) must be accepted once per server: the AI asks you, then calls `server_setup` with `acceptEula=true`.

Step-by-step guide: [Wiki](https://github.com/ImNotStable/Minecraft-Server-Creator/wiki) (source in `docs/wiki/`).

To update after pulling new code, run `installMsc` again, then reconnect msc in `/mcp` (or restart Claude Code). Claude Code can stay open: each install is a new folder. Tool reference: [msc-docs/docs/tools.md](msc-docs/docs/tools.md).

| Module | Phase |
| --- | --- |
| [msc-docs](msc-docs/README.md) | MVP |
| [msc-protocol](msc-protocol/README.md) | MVP |
| [msc-core](msc-core/README.md) | MVP |
| [msc-agent-paper](msc-agent-paper/README.md) | MVP |
| [msc-templates](msc-templates/README.md) | MVP |
| [msc-bench](msc-bench/README.md) | MVP |
| [msc-agent-fabric](msc-agent-fabric/README.md) | Post-MVP |
| [msc-agent-minestom](msc-agent-minestom/README.md) | Post-MVP |
| [msc-renderer](msc-renderer/README.md) | Post-MVP |
| [msc-textures](msc-textures/README.md) | Post-MVP |
| [msc-plugin](msc-plugin/README.md) | Post-MVP |

## Build (developing this repo)

Java 25 and Gradle 9.9 (wrapper). Gradle downloads Java 25 if it is not installed.

```
./gradlew build
```

Working conventions for humans and AI are in [CLAUDE.md](CLAUDE.md).

The full plan is in [msc-docs/docs/plan.md](msc-docs/docs/plan.md).
