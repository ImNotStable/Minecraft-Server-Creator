# {{name}}

Fabric minigame mod for Minecraft {{mcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL with Fabric Loom. Scaffolded by Minecraft Server Creator from the `fabric-game` template.

## Commands

- `./gradlew build`: compile and run all tests. Run it after every change; it must pass before you stop.
- `./gradlew test --tests '*GameCommandTest'`: one test class.
- `./gradlew runServer`: start the dev server in `run/` with this mod and Fabric API loaded. Stop it with `stop` on the console or over RCON.
- Mod jar: `build/libs/{{projectId}}-{{version}}.jar`. A server needs Fabric Loader (or Quilt Loader, which runs Fabric mods) and Fabric API installed next to it.

## What the template already does

The base every game needs, so you only write the game itself. All of it is set in `GameConfig.game()`:

- Join: inventory cleared, the `kit` given, game mode set (`ADVENTURE` by default), teleported to the player's saved stage.
- Death: inventory and levels kept (`keepInventory`, the `keep_inventory` gamerule), respawn at the current stage's spawn.
- World: time frozen at `time`, weather clear, `difficulty` applied (the dev server starts peaceful; this overrides it), natural mob spawns off (`naturalSpawns`). Mobs your code, commands and spawner blocks create still spawn.
- Stages: an ordered list, each with a `spawn` and an optional `goal` box. Stepping into the goal moves the player to the next stage. Progress is saved per player (by UUID) in `run/{{projectId}}-progress.properties` and survives restarts.
- `/game` admin command (ops, console, RCON): `status`, `stage <name|n> [player]`, `next [player]`, `reset [player]`, `kit [player]`.
- A bad setting (a stage name with a space, a duplicate stage name, no stages, a kit count of 0, a time past 23999) fails on start with a message naming it. The rules are below.

## Layout

- `{{mainClass}}.java`: entry point (`DedicatedServerModInitializer`). Only wiring here.
- `GameConfig.java`: the game's settings; `game()` is where you set rules, kit and stages. Add new settings here: a record component with a check in the constructor if it can be wrong, its value in `game()` and in the tests' `TestGames` (the compiler points at both).
- `Game.java`: game state and rules (stages, joining, advancing). Put game logic here or in classes it calls.
- `GameEvents.java`: hands Fabric API events to `Game`. `GameCommand.java`: `/game`; `handle(...)` returns the reply.
- `Stage`, `Spot`, `Box`, `Kit`: settings values. `Progress`: saved progress. `WorldRules`: gamerules, time, weather, difficulty.
- `src/main/resources/fabric.mod.json`: mod metadata, entry points and dependencies.
- `src/test/java/`: plain unit tests (`BoxTest`, `ProgressTest`, `GameConfigTest`, `SharedRulesTest`) and a command test that runs the real Brigadier tree (`GameCommandTest`). Tests play `TestGames`, a fixed two-stage game, not `GameConfig.game()`, so editing your stages, kit or rules does not break them; `GameConfigTest.theGameIsValid` checks that your `game()` passes its checks. Fabric Loader runs inside the test JVM (`fabric-loader-junit`), so Minecraft and Fabric API classes work in tests.
- `gradle/libs.versions.toml`: every dependency version. Add versions there, never inline in `build.gradle.kts`.
- `run/`: dev server. `server.properties` and `eula.txt` are committed; the world, logs and jars are not.

## Rules every game template shares

paper-game, fabric-game and minestom-game check names and values the same way, and `SharedRulesTest` holds the same table of cases in each (the same inputs, the same outcome). Change a rule in all three or in none.

- Stage names: one word of visible characters, so `/game stage <name>` can select it and chat can type it. No whitespace (not even a no-break space), no control or invisible formatting character, no `§`. A whole number is allowed: `/game stage 2` picks the stage named `2` first, then the second stage. Two names may not differ only in case (`arena` and `ARENA`), because `/game stage` ignores case (`Stage.sameName`).
- `/game` arguments are split on runs of whitespace, so `game  stage  arena  Steve` works; tab completion (paper-game, fabric-game) completes the word being typed. Player names are matched exactly, ignoring case. Subcommands ignore case.
- Values: `time` 0 to 23999; a kit amount 1 to 2304 (a full inventory); spawns finite, x and z within 30000000, y -2032 to 2031, yaw within 360, pitch within 90; goal corners whole numbers inside the same limits, in any order.
- A bad value stops the game on start with one message naming it and the rule it broke; there is no fallback.

Where this template differs, and why: the game is set in Java (`GameConfig.game()`), so the compiler checks names and types, and there are no config keys; a message names the rule and the value (`stage name must be one word of visible characters (no spaces, no §), got 'boss room'`). paper-game reads `config.yml` and names the key path instead.

## Building the game

- New stage types, waves, timers, scores: add the field to `Stage` (or `GameConfig`), the logic to `Game` (or a new class), and a test. A new field needs a value in every `new Stage(...)` (or `new GameConfig(...)`): the compiler lists them, in `game()` and the tests.
- Every piece of state you will want to check or skip to while testing gets a `/game` subcommand in `GameCommand.handle`: `game wave 5`, `game boss`, `game score Steve`. Return replies; never broadcast, so console and RCON callers see them.
- Log every state change on one line (`Game.moveTo` logs `Steve -> stage 2/3 arena (goal)`), so tests and tools can wait for it in the log.
- Build the stages in the world first, then copy their coordinates into `GameConfig.game()`. The flat dev world's standing height is y -60.
- If MSC tools are available: `server_command "game status"` shows the state, `server_command "game stage arena Steve"` skips ahead, and `server_command "tick sprint 600"` fast-forwards timers and waves.

## Fabric conventions

- Names are Mojang's official names (Minecraft 26.x is not obfuscated): `ServerPlayer`, `ServerLevel`, `BlockPos`, `Component`. There are no Yarn mappings; don't use Yarn names from old tutorials (`ServerPlayerEntity`, `Text`, `World`).
- This is a server-side mod: only server classes are on the classpath (`serverOnlyMinecraftJar()`), and `fabric.mod.json` says `"environment": "server"`. Never reference client classes.
- Prefer Fabric API events to mixins: `CommandRegistrationCallback`, `ServerPlayConnectionEvents`, `ServerLifecycleEvents`, `ServerTickEvents`, `PlayerBlockBreakEvents`, `UseBlockCallback`. Add a mixin only when no event exists; keep it small and put the logic in a normal class it calls.
- Commands are Brigadier trees built with `Commands.literal(...)` and `Commands.argument(...)`; reply with `source.sendSuccess(() -> component, false)` or `source.sendFailure(component)`.
- Text is `Component.literal(...)` / `Component.translatable(...)` with `.withStyle(ChatFormatting.X)`. Never use `§` codes.
- Never block the server thread (no I/O, sleeps or network in callbacks or commands). Do slow work on another thread, then hop back with `server.execute(...)` before touching the world.
- World, entity and inventory access only on the server thread.
- Config files go in `FabricLoader.getInstance().getConfigDir()`.
- Prefer small final classes and records. Pass dependencies through constructors; avoid static state.

## Tests

- Every change comes with a test. Pure logic: plain JUnit + AssertJ. Commands: register into a fresh `CommandDispatcher<CommandSourceStack>` and execute with a recording `CommandSource`, as `GameCommandTest` does. Code that needs a player and a world is checked on the dev server (join with the MSC test player, then `game status`).
- Code that needs registries (blocks, items, and the `Commands` class) calls `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();` once in `@BeforeAll`.
- Compare messages as plain text (`component.getString()`), not by component equality.

## Dev server

- Offline mode, bound to {{serverIp}}:{{serverPort}}, flat world (stand at y -60), no nether, no structures. The mod sets game mode and difficulty itself.
- `run/eula.txt` must say `eula=true` before the server starts. Accepting the Minecraft EULA (https://aka.ms/MinecraftEULA) is the owner's decision: if it says `false`, ask them, never change it yourself.
- RCON on port {{rconPort}}; the password is `rcon.password` in `run/server.properties`.
- If Minecraft Server Creator tools are available, use them to start the server, read condensed logs and inspect the world instead of reading raw `run/logs/latest.log`.

## Testing a game

- Chunks with no player nearby are not loaded, so nothing in them moves: mobs spawned there freeze. Keep game areas loaded with `level.setChunkForced(chunkX, chunkZ, true)` or `forceload add x1 z1 x2 z2`. Spawner blocks also need a player close by, so spawn waves from code.
- `tick sprint 600` on the console runs 600 ticks (30 game seconds) at full speed, so timers and waves come sooner.
- Log what the game does (stage started, wave cleared, player won) so tools can wait for it (`server_wait until=...`) and check it (`world_entities`).
