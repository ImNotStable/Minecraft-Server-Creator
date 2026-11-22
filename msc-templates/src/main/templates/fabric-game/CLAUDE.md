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
- `src/test/java/`: plain unit tests (`BoxTest`, `ProgressTest`, `GameConfigTest`, `SharedRulesTest`) and a command test that runs the real Brigadier tree (`GameCommandTest`). Tests play `TestGames`, a fixed two-stage game, not `GameConfig.game()`, so editing `game()` does not break them; `GameConfigTest.theGameIsValid` checks that your `game()` passes its checks. Fabric Loader runs inside the test JVM (`fabric-loader-junit`), so Minecraft and Fabric API classes work in tests.
- `gradle/libs.versions.toml`: every dependency version. Add versions there, never inline in `build.gradle.kts`.
- `run/`: dev server. `server.properties` and `eula.txt` are committed; the world, logs and jars are not.

## Rules for names and values

The game checks names and values on start. `SharedRulesTest` lists each rule as a table of inputs and whether the game takes them.

- Stage names: one word of visible characters, so `/game stage <name>` can select it and chat can type it. No whitespace (not even a no-break space), no control or invisible formatting character, no `§`. A whole number is allowed: `/game stage 2` picks the stage named `2` first, then the second stage. Two names may not differ only in case (`arena` and `ARENA`), because `/game stage` ignores case (`Stage.sameName`).
- `/game` arguments are split on runs of whitespace, so `game  stage  arena  Steve` works, and tab completion completes the word being typed. Player names are matched exactly, ignoring case. Subcommands ignore case.
- Values: `time` 0 to 23999; a kit amount 1 to 2304 (a full inventory); spawns finite, x and z within 30000000, y -2032 to 2031, yaw within 360, pitch within 90; goal corners whole numbers inside the same limits, in any order.
- A bad value stops the game on start with one message naming it and the rule it broke; there is no fallback.
- To change a rule for this game, change its check and its rows in `SharedRulesTest` together, then its line above. The game's shipped values, all in `GameConfig.game()`, must still pass the new rule: each rule below says which they are, and `./gradlew test` fails on any that do not (`GameConfigTest.theGameIsValid`). `TestGames` keeps its time inside the limits by itself. The rules you can change:
  - Time: `GameConfig.MAX_TIME`, rows in `TIMES`. Shipped: the time 6000 in `game()`; a limit below 6000 needs it set to a time it takes. A game that only allows daytime: set `MAX_TIME = 12000` in `GameConfig`, and in `SharedRulesTest.TIMES` replace `ok(23999L), no(24000L)` with `ok(12000L), no(12001L)`.
  - Kit amounts: `Kit.MAX_AMOUNT`, rows in `AMOUNTS`. Shipped: the 8 bread of the kit in `game()`; a limit below 8 needs it lowered. At most one stack: set `MAX_AMOUNT = 64` in `Kit`, and in `AMOUNTS` replace `ok(2304), no(2305)` with `no(65)`.
  - World limits: `Box.MAX_XZ`, `Box.MIN_Y` and `Box.MAX_Y`, rows in `SPAWNS` and `GOALS`. They may narrow the world to your arena, but keep x and z within 100 of 0 and y -64 to 64 inside them: the tests put their stages there. Shipped: the spawns and goal boxes of the stages in `game()`. A game within 1000 blocks at overworld heights: set `MAX_XZ = 1000`, `MIN_Y = -64` and `MAX_Y = 320` in `Box`, and in `SPAWNS` and `GOALS` write each 30000000 as 1000, 30000000.5 as 1000.5, 30000001 as 1001, 2031 as 320, 2031.5 as 320.5, 2032 as 321, -2032 as -64 and -2033 as -65 (-30000000 as -1000, and so on). Yaw within 360 and pitch within 90 are Minecraft's own limits and stay.
  - Stage names: `Stage.isName` and `Stage.NAME_RULE`, the rule as messages say it; rows in `STAGE_NAMES`, and in `NAME_PAIRS` drop a pair with a name the new rule refuses. Shipped: the stage names in `game()`. Keep lower-case words valid: the tests name their stages `a`, `start`, `arena`. ASCII names only: add `&& c < 0x80` to the check in `isName`, set `NAME_RULE = "one word of ASCII letters, digits and symbols (no spaces)"`, in `STAGE_NAMES` replace `ok("été"), ok("竜")` with `no("été"), no("竜")`, and in `NAME_PAIRS` drop `no(List.of("I", "ı"))`.

The game is set in Java (`GameConfig.game()`), so the compiler checks names and types, and there are no config keys; a message names the rule and the value (`stage name must be one word of visible characters (no spaces, no §), got 'boss room'`).

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

## Standard library (msc-stdlib)

The project depends on MSC's standard library (`libs.msc.stdlib.fabric`, shipped in `libs/repo/` with its sources, nested in the mod jar). Use it before writing your own. `Stdlib.init()` runs first in `onInitializeServer` (the template does it); `Stdlib.server()` is the running server.

Find what you need without reading sources: `grep -n "Gui" libs/stdlib/API.md` (one line per public method, with what it does), `graphify query "menu with a confirm button" --graph libs/stdlib/graphify-out/graph.json`, then read only the lines you need in `libs/stdlib/src/`. Packages are under `io.github.imnotstable.msc.stdlib`.

- Menus (server-side, vanilla clients see a chest): `Gui.chest(rows, title)` with `set(slot, item, click -> ...)`, `fill`, `border`, `editable`, `onClose`, `open(player)`; `Gui.paged(rows, title, list, icon, (entry, click) -> ...)`. Items cannot be taken out except from `editable` slots.
- Items: `ItemBuilder.of(Items.DIAMOND)` with `name`, `lore`, `glow`, `unbreakable`, `model`, `data(key, value)`, `with(component, value)`; `ItemBuilder.read(item, key)`, `ItemBuilder.filler()`, `ItemBuilder.text("Hi", ChatFormatting.GREEN)`.
- Particles: `ParticleEffect.of(ParticleTypes.FLAME)` or `ParticleEffect.dust(0xFF0000, size)`, then `spawn(level, point)`, `draw(level, origin, points)` or `play(scheduler, level, origin, period, frames, frame -> points)`. Shapes in `Shapes` (`circle`, `sphere`, `helix`, `star`, `cuboidEdges`, ...); `Locations.vec(player.position())` and `Locations.facing(player, points)`.
- Databases: `Database.open(DatabaseConfig.sqlite(path))` once `include(libs.sqlite.jdbc)` is uncommented in `build.gradle.kts` (tests already have the driver); for MySQL, MariaDB or PostgreSQL add their driver the same way and use `DatabaseConfig.server(...)`. `update`, `insert`, `query`, `queryOne`, `transaction`, `async(db -> ...)`; `Table.named(...)` schemas, `db.migrate(...)` with `Migration.of(...)`, `Store.sql(db, table, Codec.json(Account.class))` records by key, `Store.memory()` in tests.
- Player data: `PlayerData.create(store, defaults)` (in `onInitializeServer`) loads each player's record when they join, saves it when they leave and when the server stops; `get`, `update`, `saveAll`.
- Players see: `Feedback` (`success`, `fail`, `info`, `actionBar`, `title`), `Sounds` presets (`Sounds.SUCCESS.play(player)`), `Bar` boss bars (`Bar.create`, `Bar.timer`), `Hologram` text displays. Gameplay: `CustomItems.register(id, factory)` (`onUse`, `onHit`, `cooldown`), `Regions.define(name, Level.OVERWORLD, region)` (`noBuild`, `noPvp`, `onEnter`), `Prompt.ask`, `Teleports.warmup`, `Players` (`give`, `heal`, `reset`), `Lang` (placeholders filled, no tags on Fabric).
- Shared with every platform (see `libs/stdlib/API.md`): `Repository` records with no SQL, `Store` (`jsonFiles`, `cached`, `writeBehind`), `Cache`, `Economy`, `Config`, `Messenger`, `Redis`/`Mongo` (add their driver first), `Format`, `RateLimiter`, `Leaderboard`, `EventBus`, `Sequence`, `Cuboid`/`Region`, `Randoms`.
- Time: `Cooldowns` (`tryUse`, `remaining`), `Durations.parse("1h30m")`, `Durations.format`, `Stdlib.scheduler()` (`later`, `repeat`, `countdown`, `supplyAsync`). Loot: `WeightedRandom`.
- Tests that make items need Minecraft's item components: after `Bootstrap.bootStrap()`, run `BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup()).forEach(DataComponentInitializers.PendingComponents::apply)` once.

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
