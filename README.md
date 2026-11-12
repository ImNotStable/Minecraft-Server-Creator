# Minecraft Server Creator

A toolset that helps an AI develop custom Minecraft servers on any platform. This is one git repo (a monorepo); each folder is a module. The MVP targets Paper on the latest stable Minecraft release.

## Quick start (Claude Code)

1. Install the MCP server (needs nothing but this repo; Gradle fetches Java 25 if missing):

   ```
   ./gradlew :msc-core:installMsc        # Windows: gradlew.bat :msc-core:installMsc
   ```

   This puts msc-core and its Paper and Fabric agents in `~/.msc/core` and prints one line to register it.
2. Run the printed line once. It looks like this (paths are yours):

   ```
   claude mcp add msc --scope user -- "C:\Users\you\.gradle\jdks\...\bin\java.exe" -cp "C:\Users\you\.msc\core\lib\*" io.github.imnotstable.msc.core.Main
   ```

   It is also saved in `~/.msc/core/claude-mcp-add.txt`.
3. Make an empty folder, start `claude` in it and ask for a server, for example: "Make a Paper lobby plugin with a /spawn command and build a small spawn platform." The AI scaffolds the project there (`project_scaffold`), downloads Paper and installs the agent (`server_setup`), then builds, deploys and restarts in one call (`dev_cycle`).

The dev server's Minecraft EULA (https://aka.ms/MinecraftEULA) must be accepted once per server: the AI asks you, then calls `server_setup` with `acceptEula=true`.

Step-by-step guide: [Wiki](https://github.com/ImNotStable/Minecraft-Server-Creator/wiki) (source in `docs/wiki/`).

To update after pulling new code, close Claude Code (Windows locks the running jars) and run `installMsc` again. Tool reference: [msc-docs/docs/tools.md](msc-docs/docs/tools.md).

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

## Build (developing this repo)

Java 25 and Gradle 9.9 (wrapper). Gradle downloads Java 25 if it is not installed.

```
./gradlew build
```

Working conventions for humans and AI are in [CLAUDE.md](CLAUDE.md).

The full plan is in [msc-docs/docs/plan.md](msc-docs/docs/plan.md).
