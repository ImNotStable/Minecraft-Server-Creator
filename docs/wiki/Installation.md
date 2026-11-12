# Installation

You install MSC and add the `msc` plugin to Claude Code once. It takes about five minutes.

## What you need

- **Git**
- **Claude Code**
- **`java` on PATH**, any version from 17. The plugin starts with it; MSC itself runs on the Java 25 the build downloads, if the `java` on PATH is older.

You do not need to install Java 25 yourself. The build downloads it if it is missing, and MSC uses that Java.

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

   This puts MSC and its Paper and Fabric agents in a new folder under `~/.msc/core`, for example `~/.msc/core/msc-core-0.2.0-20261112-080000`.

3. Add the plugin. In Claude Code (any folder), run these once:

   ```
   /plugin marketplace add C:\path\to\Minecraft-Server-Creator
   /plugin install msc@msc
   ```

   Use the folder you cloned into, or `ImNotStable/Minecraft-Server-Creator` to add it straight from GitHub. The plugin runs a small launcher that starts the newest MSC under `~/.msc/core`, in the folder Claude Code runs in.

   Already registered MSC by hand with `claude mcp add` (older versions)? Remove that first, or you get two copies: `claude mcp remove msc -s user`. Until you do, `installMsc` keeps that older registration on the newest core too.

4. Make an empty folder for your server and start Claude Code in it.

   ```
   mkdir my-server
   cd my-server
   claude
   ```

5. Ask Claude: "Run msc_status." It should answer with the MSC version and your folder. If it does, you are done.

Next: [Quick start](Quick-start).

MSC works in whatever folder you start Claude Code in, so each server gets its own folder. You only do steps 1 to 3 once: MSC is now available in all your projects. To remove it: `/plugin uninstall msc@msc`.

## Or: download the release instead of building

Use this if you'd rather not build MSC. You need **Java 25** installed yourself (check with `java -version`), plus Claude Code.

1. Open the [latest release](https://github.com/ImNotStable/Minecraft-Server-Creator/releases/latest). You need to be signed in to GitHub with access to the repo.
2. Download `msc-core-0.1.0.zip` and unzip it into `~/.msc/core` (make the folder if needed). You get a folder `~/.msc/core/msc-core-0.1.0` with `bin/`, `lib/` and `agents/` (the Paper and Fabric agents, found automatically).
3. Add the plugin as in step 3 above. The launcher finds the unzipped folder by itself and runs it with your Java 25 (the `java` on PATH, else `JAVA_HOME`, else set `MSC_CORE_JAVA` to a Java 25 `java`).

4. Continue with steps 4 and 5 above: start `claude` in an empty folder and ask for `msc_status`.

The release also has the agents as separate jars (`msc-agent-paper`, `msc-agent-fabric`, `msc-agent-minestom`) and `SHA256SUMS.txt` to check the downloads. You only need those if you want an agent on its own.

To update: unzip the new release into `~/.msc/core` too, then reconnect msc in `/mcp`. The launcher runs whichever install it saw last.

## Updating MSC

1. In the MSC folder: `git pull`
2. Run `./gradlew :msc-core:installMsc` again. Claude Code can stay open: each install is a new folder, so no running files are touched. The two newest installs are kept.
3. In Claude Code: `/mcp`, pick msc, Reconnect (or restart Claude Code). The next `server_start` swaps in the new agent by itself. `msc_status` shows where the running core is installed, and warns when a newer install is waiting for a reconnect.

## Optional settings

- Install somewhere else: `./gradlew :msc-core:installMsc -Pmsc.installDir=<folder>`, and start Claude Code with `MSC_CORE_HOME=<folder>` so the launcher looks there.
- Working from a folder that is not the server's folder (a git worktree, another repo, a desktop app thread): MSC uses the folder Claude Code started in. Ask Claude to call `msc_workspace` with the server's folder; no restart needed.
- Other settings are environment variables. Set them in the shell you start Claude Code from; the plugin's MSC inherits them. Do not set `MSC_WORKSPACE` globally: it would point every project at one folder.

| Setting | What it does | Default |
| --- | --- | --- |
| `MSC_CORE_HOME` | Where the launcher looks for installs | `~/.msc/core` |
| `MSC_CORE_JAVA` | Java 25 `java` to run MSC with | the one the install was built with, else `java` on PATH, else `JAVA_HOME` |
| `MSC_SERVER_DIR` | Where the dev server lives | `<your folder>/run` |
| `MSC_MINECRAFT` | Minecraft version | `26.3` |
| `MSC_JAVA` | Java used to run the server | the Java running MSC |
| `MSC_CLIENT_JAR` | A Minecraft client jar, so pictures use real textures | none (flat colours) |
| `MSC_AGENT_JAR` | Use a different agent jar | a jar in `<your folder>/.msc/agent`, else the one in `~/.msc/core/agents` |

The full list is in [tools.md](https://github.com/ImNotStable/Minecraft-Server-Creator/blob/main/msc-docs/docs/tools.md#configuration-environment-variables).
