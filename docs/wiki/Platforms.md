# Platforms

MSC supports three server platforms. Each workspace uses one of them.

| Platform | What you write | Minecraft | EULA needed |
| --- | --- | --- | --- |
| **Paper** (default) | A plugin | 26.3 | Yes |
| **Fabric** | A server-side mod | 26.3 | Yes |
| **Minestom** | The whole server, from scratch | 26.2 | No |

The platform is picked by the template you scaffold. The Paper and Fabric agents are installed with MSC, and `server_setup` puts the right one in the server. You can also change it with `server_setup platform=...`.

## Paper

Best for most servers. Plugins use the Bukkit/Paper API.

1. Ask: "project_scaffold a Paper plugin called My Plugin."
2. Accept the [EULA](https://aka.ms/MinecraftEULA) yourself, then: "server_setup with acceptEula=true."
3. "Start the server." The agent goes into `plugins/`, `dev_cycle` deploys your plugin as `plugins/<jar name without version>.jar` (for example `lobby-0.1.0-SNAPSHOT.jar` becomes `plugins/lobby.jar`).

### Making a minigame: the `paper-game` template

For a game (a parkour course, wave defense, a set of trials), start from `paper-game` instead:
"project_scaffold a Paper game called Lumen Siege, template paper-game."

It is the Paper plugin template plus the base every game ends up writing. All of it is set in the project's `config.yml`:

- On join: inventory cleared, the kit given, adventure mode, teleported to the player's saved stage.
- On death: items and levels kept, respawn at the current stage.
- World: time and weather frozen, difficulty set (the dev server is peaceful otherwise), no natural mob spawns. Mobs your plugin spawns still appear.
- Stages: an ordered list, each with a spawn point and an optional goal box. Walking into the goal moves the player on. Progress is saved and survives restarts.
- A `/game` admin command, also usable from the console: `game status`, `game stage <name or number> [player]`, `game next`, `game reset`, `game kit`. Claude uses these with `server_command` to check the game and skip to any stage while testing. Every stage change is also logged, for example `Steve -> stage 2/3 arena (goal)`.

## Fabric

For mods that change the game itself. Uses Fabric Loader and Fabric API.

1. Ask: "project_scaffold a Fabric mod called My Mod, template fabric-mod."
2. Accept the [EULA](https://aka.ms/MinecraftEULA) yourself, then: "server_setup with acceptEula=true."
   This installs the Fabric server launcher, Fabric API and the agent into `mods/`.
3. "Start the server." The first start downloads the Minecraft server, so it is slower. `dev_cycle` deploys your mod as `mods/<jar name without version>.jar`.

Note: building a Fabric project needs Java 25 for Gradle (Fabric Loom). The template sets this up.

## Minestom

For fully custom servers with no vanilla game rules. There is no EULA step, because Minestom does not use Mojang's server.

1. Ask: "project_scaffold a Minestom server called My Server, template minestom-server."
   The agent is already built into the project (`libs/msc-agent-minestom.jar`), so nothing else is needed.
2. `server_setup` is not needed. It just says so if called.
3. "Start the server." `dev_cycle` builds the project's own jar and runs it as the server.

## Same tools everywhere

All world, building and texture tools work the same on every platform. The agent inside the server does the work; MSC talks to it.

| Template id | Platform |
| --- | --- |
| `paper-plugin` | Paper |
| `paper-game` | Paper (minigame base) |
| `fabric-mod` | Fabric |
| `minestom-server` | Minestom |
