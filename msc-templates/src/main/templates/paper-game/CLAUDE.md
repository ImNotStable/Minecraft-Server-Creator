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
- A bad config value (wrong type, unknown name, a typo'd key, a number out of range) disables the plugin with one log line naming the key path (`config.yml: difficulty: unknown value brutal`, `config.yml: stages[1] (arena).goal[0]: needs a whole number from -30000000 to 30000000, got the decimal -12.7`), so it shows up in the startup report. The message names the type the value has, so a quoted number says so (`got the text '6000'; remove the quotes`, only when removing them gives a valid value). Whole numbers refuse decimals; no number takes NaN or infinity; a stage name is one word (`stages[1].name: needs a stage name, one word of visible characters (no spaces, no §), got the text 'boss room'`), because `/game stage` splits on spaces; `time` is 0 to 23999, kit amounts 1 to 2304, coordinates within Minecraft's world limits (x and z within 30000000, y -2032 to 2031), yaw within 360, pitch within 90. A missing key takes its default.

## Layout

- `{{mainClass}}.java`: entry point. Only wiring here.
- `GameConfig.java`: reads and checks `config.yml`. To add a setting: its key to `KEYS`, a record component read in `from` (`wholeNumber(c, "rounds", 3)` for an `int`, `number(c, "speed", 1.5)` for a `double`, any finite number; `text`, `bool` and `list` read the other types; for a range, a `Need` such as `TIME = wholeNumber(0, MAX_TIME)` or `DROP_CHANCE = number(0, 1)` read with `TIME.get(value(c, "time"), "time", 6000)`, and its row in `ConfigNumbersTest.POSITIONS`, `setting("time", GameConfig.TIME, 0, GameConfig.MAX_TIME)`; for a range open on one side use infinity, `SPEED = number(0, Double.POSITIVE_INFINITY)` ("a number from 0 (no upper limit)") with the row `setting("speed", GameConfig.SPEED, 0, Double.POSITIVE_INFINITY)`; a NaN bound or a min above the max fails when `GameConfig` loads, naming the declaration), its default in `config.yml` and in `GameConfigTest.theShippedConfigLoadsAndMissingKeysTakeDefaults`, and a bad-value case in `GameConfigTest.BAD` (`everyKeyHasABadValueCase` fails until you do). A stage setting is the same with `STAGE_KEYS`, a `Stage` component read in `stages` (`wholeNumber(s.get("waves"), where + ".waves", 1)`, or `WAVES.get(s.get("waves"), where + ".waves", 1)` for a range, with the row `stageSetting("waves", GameConfig.WAVES, 1, 50)`) and a `Bad.stage` case.
- `Game.java`: game state and rules (stages, joining, advancing). Put game logic here or in classes it calls.
- `GameListener.java`: hands Bukkit events to `Game`. `GameCommand.java`: `/game`.
- `Stage`, `Spot`, `Box`, `Kit`: config values. `Progress`: saved progress. `WorldRules`: world-wide rules.
- `src/test/java/`: plain unit tests (`BoxTest`, `ProgressTest`, `ConfigReadersTest`) and MockBukkit tests (`GameConfigTest`, `ConfigNumbersTest`, `SharedRulesTest`, `{{mainClass}}Test`). `ConfigNumbersTest` tries every bad shape at every ranged number in the config; `ConfigReadersTest` checks the number readers at their edge bounds. `{{mainClass}}Test` plays its own two-stage game (`CONFIG`), not your `config.yml`, so editing `config.yml` does not break it.
- `gradle/libs.versions.toml`: every dependency version. `run/`: dev server config.

## Building the game

- New stage types, waves, timers, scores: add the setting to `Stage` and `GameConfig`, the logic to `Game` (or a new class), and a test.
- Every piece of state you will want to check or skip to while testing gets a `/game` subcommand: `game wave 5`, `game boss`, `game score Steve`. Reply to the sender (`sender.sendMessage`), never broadcast: broadcasts do not reach the console caller.
- Log every state change on one line (`Game.moveTo` logs `Steve -> stage 2/3 arena (goal)`), so tests and tools can wait for it in the log.
- Keep stage coordinates in `config.yml`, not in code. Build the stages in the world first, then copy their coordinates in.
- If MSC tools are available: `server_command "game status"` shows the state, `server_command "game stage arena Steve"` skips ahead, and `server_command "tick sprint 600"` fast-forwards timers and waves. Chunks with no player nearby do not tick, so put spawners and timed machinery near where players are.

## Rules for names and values

The game checks names and values on start. `SharedRulesTest` lists each rule as a table of inputs and whether the game takes them.

- Stage names: one word of visible characters, so `/game stage <name>` can select it and chat can type it. No whitespace (not even a no-break space), no control or invisible formatting character, no `§`. A whole number is allowed: `/game stage 2` picks the stage named `2` first, then the second stage. Two names may not differ only in case (`arena` and `ARENA`), because `/game stage` ignores case (`Stage.sameName`).
- `/game` arguments are split on runs of whitespace, so `game  stage  arena  Steve` works, and tab completion completes the word being typed. Player names are matched exactly, ignoring case. Subcommands ignore case.
- Values: `time` 0 to 23999; a kit amount 1 to 2304 (a full inventory); spawns finite, x and z within 30000000, y -2032 to 2031, yaw within 360, pitch within 90; goal corners whole numbers inside the same limits, in any order.
- A bad value stops the game on start with one message naming it and the rule it broke; there is no fallback.
- To change a rule for this game, change its check and its rows in `SharedRulesTest` together, then its line above. The game's shipped values must still pass the new rule: each rule below says where they are, and `./gradlew test` fails on any that do not (`GameConfigTest.theShippedConfigLoadsAndMissingKeysTakeDefaults`). The rules you can change:
  - Time: `GameConfig.MAX_TIME`, rows in `TIMES`. Shipped: `time: 6000` in `config.yml` (its comment states the range), and the default 6000 in `GameConfig.from` (`TIME.get(value(c, "time"), "time", 6000)`) and in `GameConfigTest.theShippedConfigLoadsAndMissingKeysTakeDefaults`; a limit below 6000 needs all three set to a time it takes. A game that only allows daytime: set `MAX_TIME = 12000` in `GameConfig`, and in `SharedRulesTest.TIMES` replace `ok(23999L), no(24000L)` with `ok(12000L), no(12001L)`. 6000 is still daytime, so only the range in the `time` comment of `config.yml` changes, to 0 to 12000.
  - Kit amounts: `Kit.MAX_AMOUNT`, rows in `AMOUNTS`. Shipped: `bread:8` in the `kit` of `config.yml`; a limit below 8 needs it lowered. At most one stack: set `MAX_AMOUNT = 64` in `Kit`, and in `AMOUNTS` replace `ok(2304), no(2305)` with `no(65)`.
  - World limits: `Box.MAX_XZ`, `Box.MIN_Y` and `Box.MAX_Y`, rows in `SPAWNS` and `GOALS`. They may narrow the world to your arena, but keep x and z within 100 of 0 and y -64 to 64 inside them: the tests put their stages there. Shipped: the `spawn` and `goal` of each stage in `config.yml`. A game within 1000 blocks at overworld heights: set `MAX_XZ = 1000`, `MIN_Y = -64` and `MAX_Y = 320` in `Box`, and in `SPAWNS` and `GOALS` write each 30000000 as 1000, 30000000.5 as 1000.5, 30000001 as 1001, 2031 as 320, 2031.5 as 320.5, 2032 as 321, -2032 as -64 and -2033 as -65 (-30000000 as -1000, and so on). Yaw within 360 and pitch within 90 are Minecraft's own limits and stay.
  - Stage names: `Stage.isName` and `Stage.NAME_RULE`, the rule as messages say it; rows in `STAGE_NAMES`, and in `NAME_PAIRS` drop a pair with a name the new rule refuses. Shipped: the stage names in `config.yml`. Keep lower-case words valid: the tests name their stages `a`, `start`, `arena` and `boss`. `GameConfigTest.aStageNamedLikeANumberWinsOverTheNumber` runs only while the rule takes `2`. ASCII names only: add `&& c < 0x80` to the check in `isName`, set `NAME_RULE = "one word of ASCII letters, digits and symbols (no spaces)"`, in `STAGE_NAMES` replace `ok("été"), ok("竜")` with `no("été"), no("竜")`, and in `NAME_PAIRS` drop `no(List.of("I", "ı"))`.

Every message names the key path in `config.yml` (`stages[1].name: needs a stage name, ...`), a typo'd key fails, and a value of the wrong YAML type fails (`name: 2` is a number; write `name: '2'`).

## Paper conventions

- Text is Adventure `Component`s, written as MiniMessage (`<green>Hi <player>`) or `Component.text(..., NamedTextColor.X)`. Never use `ChatColor` or `§` codes. Insert player input with `Placeholder.unparsed`.
- Commands use Paper's Brigadier API, registered in `onEnable` via `getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)`. Do not add commands to `plugin.yml`.
- Never block the main thread (no I/O, sleeps or network in event handlers or commands). `Progress` shows the pattern: a change only updates memory and marks it dirty; `flush()` builds the file text and writes it on an async task.
- World, entity and inventory access only on the main thread.
- Gamerules are `org.bukkit.GameRules` constants (`GameRules.KEEP_INVENTORY`, `GameRules.ADVANCE_TIME`, `GameRules.SPAWN_MOBS`); the old camelCase names are gone in 26.x.
- Prefer small final classes and records, except the main plugin class: MockBukkit must subclass it, so never make it final. Pass dependencies through constructors; avoid static state so MockBukkit tests stay isolated.

## Tests

- Every change comes with a test. Pure logic: plain JUnit + AssertJ. Events, commands, players: MockBukkit.
- MockBukkit pattern (see `{{mainClass}}Test`): `MockBukkit.mock()`, `server.addSimpleWorld("world")`, then `MockBukkit.loadWithConfig({{mainClass}}.class, config)` with the test's own config; `server.addPlayer("Steve")` fires a join; `server.dispatchCommand(server.getConsoleSender(), "game status")` runs a console command; fire events with `server.getPluginManager().callEvent(...)`.
- Compare messages as plain text (`PlainTextComponentSerializer`), not by component equality.

## Dev server

- Offline mode, bound to {{serverIp}}:{{serverPort}}, flat world (stand at y -60), no nether, no structures. The plugin sets game mode and difficulty itself.
- `run/eula.txt` must say `eula=true` before the server starts. Accepting the Minecraft EULA (https://aka.ms/MinecraftEULA) is the owner's decision: if it says `false`, ask them, never change it yourself.
- RCON on port {{rconPort}}; the password is `rcon.password` in `run/server.properties`.
- If Minecraft Server Creator tools are available, use them to start the server, read condensed logs and inspect the world instead of reading raw `run/logs/latest.log`.
