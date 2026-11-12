# Installation

You build MSC once, then tell Claude Code where it is. It takes about five minutes.

## What you need

- **Java 25.** MSC runs on it, and so does the dev server. Check with `java -version`.
- **Git.**
- **Claude Code** (or another app that can use MCP servers).

## Steps

1. Get the code.

   ```
   git clone https://github.com/ImNotStable/Minecraft-Server-Creator.git
   cd Minecraft-Server-Creator
   ```

2. Build the MCP server and the Paper agent.

   ```
   ./gradlew :msc-core:installDist :msc-agent-paper:shadowJar
   ```

   On Windows use `gradlew.bat` instead of `./gradlew`. The first build downloads Gradle and its libraries, so it can take a few minutes.

3. Check that two things now exist:
   - `msc-core/build/install/msc-core/lib/` (the MCP server)
   - `msc-agent-paper/build/libs/msc-agent-paper-0.1.0-SNAPSHOT.jar` (the Paper agent)

4. Make an **empty** folder for your server project, for example `~/my-server`, and go into it. Keep it empty: MSC puts the new project straight into this folder.

   ```
   mkdir ~/my-server
   cd ~/my-server
   ```

5. Add MSC to Claude Code for this folder. Replace `/path/to/Minecraft-Server-Creator` with where you cloned MSC. Keep the quotes around the `lib/*` path.

   ```
   claude mcp add msc \
     -e MSC_WORKSPACE="$PWD" \
     -e MSC_AGENT_JAR=/path/to/Minecraft-Server-Creator/msc-agent-paper/build/libs/msc-agent-paper-0.1.0-SNAPSHOT.jar \
     -- java -cp "/path/to/Minecraft-Server-Creator/msc-core/build/install/msc-core/lib/*" io.github.imnotstable.msc.core.Main
   ```

   On Windows (PowerShell), put it on one line, use `$PWD` the same way, and write paths with forward slashes, such as `D:/Projects/Minecraft-Server-Creator/...`.

   This saves the setting in Claude Code's own config, not in your folder, so the folder stays empty.

6. Start Claude Code in that folder: `claude`.

7. Ask Claude: "Run msc_status." It should answer with the MSC version and your workspace. If it does, you are done.

Next: [Quick start](Quick-start).

## Optional settings

Add these with more `-e NAME=value` parts in step 5 if you need them.

| Setting | What it does | Default |
| --- | --- | --- |
| `MSC_WORKSPACE` | Your project folder | the folder Claude Code started in |
| `MSC_SERVER_DIR` | Where the dev server lives | `<workspace>/run` |
| `MSC_AGENT_JAR` | The agent plugin or mod to put in the server | a jar in `<workspace>/.msc/agent` |
| `MSC_MINECRAFT` | Minecraft version | `26.3` |
| `MSC_JAVA` | Java used to run the server | the Java running MSC |
| `MSC_CLIENT_JAR` | A Minecraft client jar, so pictures use real textures | none (flat colours) |

The full list is in [msc-docs/docs/tools.md](https://github.com/ImNotStable/Minecraft-Server-Creator/blob/main/msc-docs/docs/tools.md#configuration-environment-variables).

## Updating MSC

1. `git pull` in the MSC folder.
2. Run the build command from step 2 again.
3. Restart Claude Code. The next `server_start` swaps in the new agent jar by itself.
