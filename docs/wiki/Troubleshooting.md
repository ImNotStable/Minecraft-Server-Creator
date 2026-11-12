# Troubleshooting

Find your problem, then try the steps in order.

## Claude Code does not show the msc tools

1. Run `/plugin` in Claude Code and check that `msc` is installed and enabled. If not, add it (see [Installation](Installation), step 3).
2. Run `/mcp` and pick msc. If it failed to start, its message says why in one line: no install under `~/.msc/core` (run `./gradlew :msc-core:installMsc`), or no Java 25 (set `MSC_CORE_JAVA` to a Java 25 `java`).
3. `java` must be on PATH (any version from 17): `java -version` in the shell you start Claude Code from.
4. Reconnect msc in `/mcp`, or restart Claude Code.

## "the Minecraft EULA is not accepted"

Paper and Fabric need it. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA). If you agree, tell Claude: "I accept the EULA, run server_setup with acceptEula=true." Or set `eula=true` in `run/eula.txt` yourself. Or accept it once for this machine: run the line in `~/.msc/core/eula-accept.txt` in a terminal and type `yes`; every later Paper and Fabric dev server then starts without asking. Minestom does not need it.

## "target exists and is not an empty directory"

`project_scaffold` only writes into an empty folder. Settings folders such as `.msc`, `.claude`, `.git`, `.idea`, `.vscode` and a `.mcp.json` file are fine. Either:

1. Start again in an empty folder, or
2. Ask Claude to scaffold into a subfolder with `dir=my-plugin`.

## The server starts but "agent not connected"

1. MSC uses the agent installed with it (`~/.msc/core/agents`) unless `MSC_AGENT_JAR` is set. Re-run `./gradlew :msc-core:installMsc` (with Claude Code closed) so it is current, then `server_setup` again.
2. Ask Claude for `msc_status`. It shows the agent state.
3. Ask for `server_logs` to see errors from the agent.
4. Minestom: the agent is built into the template. Check the project still calls `MscAgent.start()`.

## World tools say "the server is not running"

Start the server first with `server_start`. World, build and pack tools need a running server with the agent connected.

## The build fails in dev_cycle

`dev_cycle` shows only the compiler and test errors. Let Claude fix them and call `dev_cycle` again. For Fabric, Gradle must run on Java 25.

## installMsc refuses the install folder

With `-Pmsc.installDir`, the folder must be empty or hold an earlier MSC install. Pick an empty folder.

## Install fails on Windows with "file in use"

Claude Code is still running MSC. Close Claude Code, then run `installMsc` again.

## "cannot replace ... .jar" or "another process has locked a portion of the file"

A server from an earlier Claude Code session is still running. Ask Claude to run `server_stop` (it also stops a server left by an earlier session), then `dev_cycle`.

## Port already in use

Another server is running. Stop it, ask Claude to `server_stop`, or change `server-port` and `rcon.port` in `run/server.properties`. The dev server uses 25565 (game) and 25575 (RCON) by default.

## Pictures look flat

`world_render` uses plain colours unless you set `MSC_CLIENT_JAR` to a Minecraft client jar you have. Your own pack's textures are always used.

## "warning: the workspace is the Minecraft Server Creator repo"

Claude Code was started in the MSC folder itself, so MSC treats the repo as your server project. Start Claude Code in your server's folder instead. To work from another folder anyway, ask Claude to call `msc_workspace` with the server's folder.

## "warning: this msc-core ... is older than its checkout"

The MSC source changed after you installed it (for example after `git pull`), so the running tools are out of date. Close Claude Code, run `./gradlew :msc-core:installMsc` in the MSC folder, and open Claude Code again.

## Still stuck

Open an issue on GitHub with what you asked, what happened, and the output of `msc_status` and `server_logs`.
