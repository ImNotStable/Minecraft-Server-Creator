# Troubleshooting

Find your problem, then try the steps in order.

## Claude Code does not show the msc tools

1. Run `claude mcp list` in your project folder. `msc` should be listed.
2. Check `java -version` says 25 or newer.
3. Check the `lib/*` path from [Installation](Installation) step 5 exists, and that it is in quotes.
4. Restart Claude Code.

## "the Minecraft EULA is not accepted"

Paper and Fabric need it. Read the [Minecraft EULA](https://aka.ms/MinecraftEULA). If you agree, tell Claude: "I accept the EULA, run server_setup with acceptEula=true." Or set `eula=true` in `run/eula.txt` yourself. Minestom does not need it.

## "target exists and is not an empty directory"

`project_scaffold` only writes into an empty folder (an `.msc` folder is fine). Either:

1. Start again in an empty folder, or
2. Ask Claude to scaffold into a subfolder with `dir=my-plugin`.

If your folder has a `.mcp.json` or `.claude` folder in it, register MSC with `claude mcp add` instead (see [Installation](Installation)).

## The server starts but "agent not connected"

1. Check `MSC_AGENT_JAR` points at a jar that exists, for the right platform (Paper agent for Paper, Fabric agent for Fabric).
2. Ask Claude for `msc_status`. It shows the agent state.
3. Ask for `server_logs` to see errors from the agent.
4. Minestom: the agent is built into the template. Check the project still calls `MscAgent.start()`.

## World tools say no server or no agent

Start the server first with `server_start`. World, build and pack tools need a running server with the agent connected.

## The build fails in dev_cycle

`dev_cycle` shows only the compiler and test errors. Let Claude fix them and call `dev_cycle` again. For Fabric, Gradle must run on Java 25.

## Port already in use

Another server is running. Stop it, or ask Claude to `server_stop`. The dev server uses 25565 (game) and 25575 (RCON) by default.

## Pictures look flat

`world_render` uses plain colours unless you set `MSC_CLIENT_JAR` to a Minecraft client jar you have. Your own pack's textures are always used.

## Still stuck

Open an issue on GitHub with what you asked, what happened, and the output of `msc_status` and `server_logs`.
