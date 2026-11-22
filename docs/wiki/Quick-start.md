# Quick start: your first Paper server

Do [Installation](Installation) first. Then open Claude Code in your server folder and follow these steps. You can type the requests in your own words; the examples below are just a guide.

## Steps

1. **Create the project.**
   Ask: "Use project_scaffold to make a Paper plugin called Spawn Lobby."
   You get a Gradle project with a `/hello` command, tests, and a `CLAUDE.md` that tells the AI how to work in it.

2. **Read the Minecraft EULA.**
   Open [aka.ms/MinecraftEULA](https://aka.ms/MinecraftEULA). A Paper server will not start until you accept it. Only you can do this. Claude will ask you; it must not accept it for you.
   To accept once for this machine instead of for every server, install with `-Pmsc.acceptEula=true`, or run the line in `~/.msc/core/eula-accept.txt` yourself and type `yes`. Your first yes to Claude (`acceptEula=true`) also covers the machine. Either way you confirm you accept Mojang's EULA, as set out in [TERMS.md](https://github.com/ImNotStable/Minecraft-Server-Creator/blob/main/TERMS.md). Undo it with the same line, `revoke` instead of `accept`.

3. **Set up the server.**
   If you accept the EULA, say: "I accept the Minecraft EULA. Run server_setup with acceptEula=true." If you accepted it for this machine, just say "Run server_setup."
   MSC downloads Paper, writes the dev server settings and installs the agent plugin.

4. **Start the server.**
   Ask: "Start the server."
   The answer looks like `server ready in 8.2s, agent connected`, plus a line saying where the ground is, such as `ground is y=-61 (most common top block around 0,0; build from y=-60)`.

5. **Ask for a feature.**
   For example: "Add a /spawn command that teleports the player to 0, -60, 0."
   Claude edits the code and calls `dev_cycle`. That builds, deploys, restarts and reports only the errors, including the names of failed tests. It repeats until it sees `no errors or warnings`.

6. **Try it.**
   Check it loaded: "Run plugins with server_command." To try `/spawn` itself, join the game at `127.0.0.1:25565` (offline mode) and type `/spawn`. Console commands can't run player-only commands.

7. **Build something.**
   Ask: "Build a small stone hut at 10, -60, 10 and show me a picture of it."
   Claude uses `build_batch`, then `world_render`. Every build can be undone with `build_undo`.

8. **Stop the server** when you are done: "Stop the server."

## Tips

- The dev world is flat. The ground is at y = -61, so builds start at y = -60.
- To play on the server after you close Claude Code, ask Claude to "detach the server" first (`server_detach`). The server and the resource pack stay up. The next session stops them, saving the world, when it starts the server again.
- Ask Claude to look up names with `api_search` instead of guessing. It saves time and tokens.
- `msc_status stats=true` shows how many calls and tokens each tool used.
- Templates are bare: a Gradle build and an empty entry point, so Claude writes your game or plugin the way you describe it. See [Platforms](Platforms#what-a-template-contains).
- Want Fabric or Minestom instead? See [Platforms](Platforms).
- Something went wrong? See [Troubleshooting](Troubleshooting).
