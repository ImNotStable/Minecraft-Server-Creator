# {{name}}

Paper minigame plugin for Minecraft {{mcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL. Scaffolded by Minecraft Server Creator from the `paper-game` template.

## Commands

- `./gradlew build`: compile and run all tests. Run it after every change; it must pass before you stop.
- `./gradlew test --tests '*GameConfigTest'`: one test class.
- `./gradlew runServer`: start the dev server in `run/` with this plugin loaded. Stop it with `stop` on the console or over RCON.
- Plugin jar: `build/libs/{{projectId}}-{{version}}.jar`.

## What the template already does

The base every game needs, so you only write the game itself. All of it is set in `src/main/resources/config.yml`:

- Join: inventory cleared, the `kit` given, game mode set (`adventure` by default), teleported to the player's saved stage.
- Death: inventory and levels kept (`keep-inventory`), respawn at the current stage's spawn.
- World: time frozen at `time`, weather clear, `difficulty` applied (the dev server starts peaceful; this overrides it), natural mob spawns off (`natural-spawns`). Mobs your code, commands and spawner blocks create still spawn.
- Stages: an ordered list, each with a `spawn` and an optional `goal` box. Stepping into the goal moves the player to the next stage. Progress is saved per player in `plugins/{{name}}/progress.yml` and survives restarts.
- `/game` admin command (op or `{{projectId}}.admin`): `status`, `stage <name|n> [player]`, `next [player]`, `reset [player]`, `kit [player]`.
- A bad config value (wrong type, unknown name, a typo'd key) disables the plugin with one log line naming the key (`config.yml: difficulty: unknown value brutal`, `config.yml: time: needs a whole number, got the text 'night'`), so it shows up in the startup report. The message names the type the value has, so a quoted number says so: `got the text '6000'; remove the quotes`. A missing key takes its default.

## Layout

- `{{mainClass}}.java`: entry point. Only wiring here.
- `GameConfig.java`: reads and checks `config.yml`. Add new settings here: the key to `KEYS` (or `STAGE_KEYS`), a default in `config.yml`, and a bad-value case in `GameConfigTest` (its `everyKeyHasABadValueCase` fails until you do).
- `Game.java`: game state and rules (stages, joining, advancing). Put game logic here or in classes it calls.
- `GameListener.java`: hands Bukkit events to `Game`. `GameCommand.java`: `/game`.
- `Stage`, `Spot`, `Box`, `Kit`: config values. `Progress`: saved progress. `WorldRules`: world-wide rules.
- `src/test/java/`: plain unit tests (`BoxTest`, `ProgressTest`) and MockBukkit tests (`GameConfigTest`, `{{mainClass}}Test`).
- `gradle/libs.versions.toml`: every dependency version. `run/`: dev server config.

## Building the game

- New stage types, waves, timers, scores: add the setting to `Stage` and `GameConfig`, the logic to `Game` (or a new class), and a test.
- Every piece of state you will want to check or skip to while testing gets a `/game` subcommand: `game wave 5`, `game boss`, `game score Steve`. Reply to the sender (`sender.sendMessage`), never broadcast: broadcasts do not reach the console caller.
- Log every state change on one line (`Game.moveTo` logs `Steve -> stage 2/3 arena (goal)`), so tests and tools can wait for it in the log.
- Keep stage coordinates in `config.yml`, not in code. Build the stages in the world first, then copy their coordinates in.
- If MSC tools are available: `server_command "game status"` shows the state, `server_command "game stage arena Steve"` skips ahead, and `server_command "tick sprint 600"` fast-forwards timers and waves. Chunks with no player nearby do not tick, so put spawners and timed machinery near where players are.

## Paper conventions

- Text is Adventure `Component`s, written as MiniMessage (`<green>Hi <player>`) or `Component.text(..., NamedTextColor.X)`. Never use `ChatColor` or `§` codes. Insert player input with `Placeholder.unparsed`.
- Commands use Paper's Brigadier API, registered in `onEnable` via `getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)`. Do not add commands to `plugin.yml`.
- Never block the main thread (no I/O, sleeps or network in event handlers or commands). `Progress` shows the pattern: a change only updates memory and marks it dirty; `flush()` builds the file text and writes it on an async task.
- World, entity and inventory access only on the main thread.
- Gamerules are `org.bukkit.GameRules` constants (`GameRules.KEEP_INVENTORY`, `GameRules.ADVANCE_TIME`, `GameRules.SPAWN_MOBS`); the old camelCase names are gone in 26.x.
- Prefer small final classes and records, except the main plugin class: MockBukkit must subclass it, so never make it final. Pass dependencies through constructors; avoid static state so MockBukkit tests stay isolated.

## Tests

- Every change comes with a test. Pure logic: plain JUnit + AssertJ. Events, commands, players: MockBukkit.
- MockBukkit pattern (see `{{mainClass}}Test`): `MockBukkit.mock()`, `server.addSimpleWorld("world")`, then `MockBukkit.load({{mainClass}}.class)`; `server.addPlayer("Steve")` fires a join; `server.dispatchCommand(server.getConsoleSender(), "game status")` runs a console command; fire events with `server.getPluginManager().callEvent(...)`.
- Compare messages as plain text (`PlainTextComponentSerializer`), not by component equality.

## Dev server

- Offline mode, bound to {{serverIp}}:{{serverPort}}, flat world (stand at y -60), no nether, no structures. The plugin sets game mode and difficulty itself.
- `run/eula.txt` must say `eula=true` before the server starts. Accepting the Minecraft EULA (https://aka.ms/MinecraftEULA) is the owner's decision: if it says `false`, ask them, never change it yourself.
- RCON on port {{rconPort}}; the password is `rcon.password` in `run/server.properties`.
- If Minecraft Server Creator tools are available, use them to start the server, read condensed logs and inspect the world instead of reading raw `run/logs/latest.log`.
