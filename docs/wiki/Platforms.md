# Platforms

MSC supports eight server platforms and the Velocity proxy. Each workspace uses one of them.

| Platform | What you write | Template | Minecraft | EULA needed |
| --- | --- | --- | --- | --- |
| **Paper** (default) | A plugin | `paper-plugin`, `paper-game` | 26.3 | Yes |
| **Purpur** | A Paper plugin (Purpur is a Paper fork) | `paper-plugin`, `paper-game` | 26.3 | Yes |
| **Folia** | A Paper plugin with `folia-supported: true` (Folia is Paper with regions on many threads) | `paper-plugin`, `paper-game` | 26.3 | Yes |
| **Fabric** | A server-side mod | `fabric-mod`, `fabric-game` | 26.3 | Yes |
| **Quilt** | A Fabric mod (Quilt loads Fabric mods) | `fabric-mod`, `fabric-game` | 26.3 | Yes |
| **NeoForge** | A server-side mod | `neoforge-mod` | 26.3 | Yes |
| **Forge** | A server-side mod | `forge-mod` | 26.3 | Yes |
| **Minestom** | The whole server, from scratch | `minestom-server`, `minestom-game` | 26.2 | No |
| **Velocity** | A proxy plugin (players reach a Paper backend through it) | `velocity-plugin` | any recent | No |

The platform is picked by the template you scaffold. The agents are installed with MSC, and `server_setup` puts the right one in the server. You can also change it with `server_setup platform=...`, which is how you pick Purpur or Quilt for a Paper or Fabric project.

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

For a game, use `template fabric-game`: the same minigame base as [`paper-game`](#making-a-minigame-the-paper-game-template), set in `GameConfig.game()` in Java instead of `config.yml`.

## Purpur and Quilt

Purpur runs Paper plugins and Quilt runs Fabric mods, so they use the Paper and Fabric templates and agents.

1. Scaffold as for Paper or Fabric.
2. "server_setup platform=purpur" (or `platform=quilt`). Purpur comes from Purpur's download service; Quilt runs Quilt's installer and adds Fabric API to `mods/`.
3. `dev_cycle` keeps the workspace on Purpur or Quilt even though the project looks like Paper or Fabric.

## Folia

Folia runs Paper plugins that say they support it, with the world split into regions ticked on several threads.

1. Scaffold as for Paper, then add `folia-supported: true` to `plugin.yml` and use Folia's region and entity schedulers instead of `getScheduler()`.
2. Accept the [EULA](https://aka.ms/MinecraftEULA) yourself, then: "server_setup platform=folia with acceptEula=true."
3. `dev_cycle`, `server_command`, `server_logs` and `server_wait` work as on Paper. World, build and player tools do not work on Folia yet: they say so in one line. See [ADR 0045](https://github.com/ImNotStable/Minecraft-Server-Creator/blob/main/msc-docs/docs/adr/0045-folia-and-proxies.md).

## Velocity (proxy)

A proxy plugin needs two servers: the Velocity proxy, and a Paper (or Purpur or Folia) backend behind it. Each gets its own workspace.

1. Backend: in a Paper workspace, "server_setup proxy=velocity", start it, and "server_detach" if you switch workspaces. Players then join it only through the proxy.
2. Proxy: "project_scaffold a Velocity plugin called My Proxy Plugin, template velocity-plugin", then "server_setup platform=velocity" (no EULA step) and `dev_cycle`.
3. Join `localhost:25577`. The proxy sends you to the backend on `127.0.0.1:25565`, using a forwarding secret MSC keeps for your machine in `~/.msc/velocity.secret`.

World, build and player tools do not apply to a proxy; use them in the backend's workspace. BungeeCord and Waterfall are not supported.

## NeoForge and Forge

For mods on the Forge family of loaders.

1. Ask: "project_scaffold a NeoForge mod called My Mod, template neoforge-mod" (or `forge-mod`).
   The mod id is the project id with `_` for `-` (`my_mod`): these loaders allow no hyphens.
2. Accept the [EULA](https://aka.ms/MinecraftEULA) yourself, then: "server_setup with acceptEula=true."
   This runs the loader's own installer in the server folder (a few minutes the first time) and puts the agent in `mods/`.
3. "Start the server." `dev_cycle` deploys your mod into `mods/`.

Right after scaffolding, MSC starts the project's first build in the background, because these loaders set up Minecraft once (several minutes for Forge). `dev_cycle` waits for it if it is still running.

`neoforge-mod` builds with ModDevGradle and runs its tests inside NeoForge, so command tests use the real Minecraft classes. `forge-mod` builds with ForgeGradle 7; its tests are plain JUnit.

## Minestom

For fully custom servers with no vanilla game rules. There is no EULA step, because Minestom does not use Mojang's server.

1. Ask: "project_scaffold a Minestom server called My Server, template minestom-server."
   The agent is already built into the project (`libs/msc-agent-minestom.jar`), so nothing else is needed.
2. `server_setup` is not needed. It just says so if called.
3. "Start the server." `dev_cycle` builds the project's own jar and runs it as the server.

For a game, use `template minestom-game`: the same minigame base as [`paper-game`](#making-a-minigame-the-paper-game-template), set in `GameConfig.game()`, with a `GameTest` that plays it through Minestom's `@EnvTest` players. No EULA step, so it is the quickest base for a prototype.

## Same tools everywhere

All world, building and texture tools work the same on every platform but Folia and Velocity (see above). The agent inside the server does the work; MSC talks to it.

| Template id | Platform |
| --- | --- |
| `paper-plugin` | Paper |
| `paper-game` | Paper (minigame base) |
| `fabric-mod` | Fabric |
| `fabric-game` | Fabric (minigame base) |
| `neoforge-mod` | NeoForge |
| `forge-mod` | Forge |
| `minestom-server` | Minestom |
| `minestom-game` | Minestom (minigame base) |
| `velocity-plugin` | Velocity (proxy) |

## The standard library

Paper, Minestom and Fabric projects (`paper-plugin`, `paper-game`, `minestom-server`, `minestom-game`, `fabric-mod`, `fabric-game`) come with MSC's standard library, `msc-stdlib`, so the AI does not rewrite the usual parts of a plugin each time. It is in the project's `libs/repo/` folder, listed in `gradle/libs.versions.toml`, and packed into the plugin, server or mod jar, so it needs nothing else on the server.

- Data: SQLite by default, or MySQL, MariaDB, PostgreSQL or H2, plus MongoDB and Redis. Records are saved without writing SQL, with caches, an economy, typed settings files and messages between servers.
- Players see: menus with themes, layouts, confirm screens and live updates; titles, action bars, boss bars, sounds, holograms, sidebars and name tags.
- Gameplay: commands, custom items, protected regions, chat prompts, teleport warm-ups, translations, particle shapes and animations.
- Utilities: number and time formatting, progress bars, cooldowns, rate limits, leaderboards, events, timed sequences, loot tables.

Each project also gets an index of the library (one line per method), its sources and a graphify graph of it, so the AI looks up only what it needs.

NeoForge, Forge and Velocity get it in later releases. Design: ADR 0066.
