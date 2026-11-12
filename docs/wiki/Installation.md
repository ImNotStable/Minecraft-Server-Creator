# Installation

You install MSC once and register it with Claude Code. It takes about five minutes.

## What you need

- **Git**
- **Claude Code**

You do not need to install Java yourself. The build downloads Java 25 if it is missing, and MSC uses that Java.

## Steps

1. Get the code.

   ```
   git clone https://github.com/ImNotStable/Minecraft-Server-Creator.git
   cd Minecraft-Server-Creator
   ```

2. Install MSC.

   ```
   ./gradlew :msc-core:installMsc
   ```

   On Windows: `gradlew.bat :msc-core:installMsc`. The first run downloads Gradle, Java and libraries, so it can take a few minutes.

   This puts MSC and its Paper and Fabric agents in `~/.msc/core`, and prints one line that starts with `claude mcp add msc`.

3. Copy that printed line and run it. It looks like this (your paths will differ):

   ```
   claude mcp add msc --scope user -- "C:\Users\you\.gradle\jdks\...\bin\java.exe" -cp "C:\Users\you\.msc\core\lib\*" io.github.imnotstable.msc.core.Main
   ```

   Lost it? It is saved in `~/.msc/core/claude-mcp-add.txt`.

4. Make an empty folder for your server and start Claude Code in it.

   ```
   mkdir my-server
   cd my-server
   claude
   ```

5. Ask Claude: "Run msc_status." It should answer with the MSC version and your folder. If it does, you are done.

Next: [Quick start](Quick-start).

MSC works in whatever folder you start Claude Code in, so each server gets its own folder. You only do steps 1 to 3 once: MSC is now available in all your projects. To remove it: `claude mcp remove msc -s user`.

## Updating MSC

1. Close Claude Code. (On Windows the running files are locked.)
2. In the MSC folder: `git pull`
3. Run `./gradlew :msc-core:installMsc` again.
4. Open Claude Code. The next `server_start` swaps in the new agent by itself.

## Optional settings

- Install somewhere else: `./gradlew :msc-core:installMsc -Pmsc.installDir=<folder>`, then run the new line it prints. The folder must be empty or hold an earlier MSC install.
- Other settings are environment variables, added to the register line as `-e NAME=value` before the `--`. Do not set `MSC_WORKSPACE` there: with `--scope user` it would point every project at one folder.

| Setting | What it does | Default |
| --- | --- | --- |
| `MSC_SERVER_DIR` | Where the dev server lives | `<your folder>/run` |
| `MSC_MINECRAFT` | Minecraft version | `26.3` |
| `MSC_JAVA` | Java used to run the server | the Java running MSC |
| `MSC_CLIENT_JAR` | A Minecraft client jar, so pictures use real textures | none (flat colours) |
| `MSC_AGENT_JAR` | Use a different agent jar | a jar in `<your folder>/.msc/agent`, else the one in `~/.msc/core/agents` |

The full list is in [tools.md](https://github.com/ImNotStable/Minecraft-Server-Creator/blob/main/msc-docs/docs/tools.md#configuration-environment-variables).
