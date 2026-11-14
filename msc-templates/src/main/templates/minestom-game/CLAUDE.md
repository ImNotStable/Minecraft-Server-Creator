# {{name}}

Minestom minigame server for Minecraft {{minestomMcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL. Scaffolded by Minecraft Server Creator from the `minestom-game` template.

## Commands

- `./gradlew build`: compile, run all tests and build the server jar. Run it after every change; it must pass before you stop.
- `./gradlew test --tests '*GameTest'`: one test class.
- `./gradlew run`: start the dev server in `run/` on {{serverIp}}:{{serverPort}}. Type `game status` or `stop` on the console.
- Server jar (runs on its own): `build/libs/{{projectId}}-{{version}}.jar`, start with `java -jar` from `run/`.

## What the template already does

The base every game needs, so you only write the game itself. All of it is set in `GameConfig.game()`:

- Join: inventory cleared, the `kit` given, game mode set (`ADVENTURE` by default), teleported to the player's saved stage.
- Death: Minestom drops nothing, so players keep their items; they respawn at the current stage's spawn.
- World: time frozen at `time`, weather clear, `difficulty` set. There are no natural mob spawns in Minestom at all.
- Stages: an ordered list, each with a `spawn` and an optional `goal` box. Stepping into the goal moves the player to the next stage. Progress is saved per player (by UUID) in `run/progress.properties` and survives restarts. Offline (the dev server) players get vanilla's offline UUID from their name, so progress also survives a reconnect.
- `/game` admin command (console always; players named in `admins`): `status`, `stage <name|n> [player]`, `next [player]`, `reset [player]`, `kit [player]`.
- A bad setting (a stage name with a space, a duplicate stage name, no stages, a kit amount of 0, a time past 23999) fails on start with a message naming it. The rules are below.

## Layout

- `{{name}}Server.java`: entry point. Only wiring here (world, game, events, commands).
- `GameConfig.java`: the game's settings; `game()` is where you set kit, stages and admins. Add new settings here: a record component with a check in the constructor if it can be wrong, its value in `game()` and in the tests' `TestGames` (the compiler points at both).
- `Game.java`: game state and rules (stages, joining, advancing). Put game logic here or in classes it calls.
- `GameEvents.java`: hands Minestom events to `Game`. `GameCommand.java`: `/game`; `handle(...)` returns the reply, so tests call it directly.
- `Stage`, `Box`, `Kit`: settings values. `Progress`: saved progress. `Console`: reads `stop` and commands from standard input, and runs commands on the tick thread.
- `src/test/java/`: plain unit tests (`BoxTest`, `GameConfigTest`, `ProgressTest`, `SharedRulesTest`) and test-environment tests (`GameTest`, `GameThreadsTest`, `{{name}}ServerTest`). Tests play `TestGames`, a fixed two-stage game, not `GameConfig.game()`, so editing your stages, kit or admins does not break them; `GameConfigTest.theGameIsValid` checks that your `game()` passes its checks.
- `gradle/libs.versions.toml`: every dependency version. `libs/msc-agent-minestom.jar`: Minecraft Server Creator's agent; keep it, do not edit it.

## Rules every game template shares

paper-game, fabric-game and minestom-game check names and values the same way, and `SharedRulesTest` holds the same table of cases in each (the same inputs, the same outcome). Change a rule in all three or in none.

- Stage names: one word of visible characters, so `/game stage <name>` can select it and chat can type it. No whitespace (not even a no-break space), no control or invisible formatting character, no `§`. A whole number is allowed: `/game stage 2` picks the stage named `2` first, then the second stage. Two names may not differ only in case (`arena` and `ARENA`), because `/game stage` ignores case (`Stage.sameName`).
- `/game` arguments are split on runs of whitespace, so `game  stage  arena  Steve` works; tab completion (paper-game, fabric-game) completes the word being typed. Player names are matched exactly, ignoring case. Subcommands ignore case.
- Values: `time` 0 to 23999; a kit amount 1 to 2304 (a full inventory); spawns finite, x and z within 30000000, y -2032 to 2031, yaw within 360, pitch within 90; goal corners whole numbers inside the same limits, in any order.
- A bad value stops the game on start with one message naming it and the rule it broke; there is no fallback.

Where this template differs, and why: the game is set in Java (`GameConfig.game()`), so the compiler checks names and types, and there are no config keys; a message names the rule and the value (`stage name must be one word of visible characters (no spaces, no §), got 'boss room'`). paper-game reads `config.yml` and names the key path instead.
Minestom's `Pos` wraps yaw into range and clamps pitch to 90 itself, so a spawn's view direction is never refused here (paper-game and fabric-game refuse yaw past 360 and pitch past 90). Minestom's `/game` has no tab completion: its argument is a plain string array. `admins` is Minestom's own (Paper and Fabric use permissions).

## Building the game

- New stage types, waves, timers, scores: add the field to `Stage` (or `GameConfig`), the logic to `Game` (or a new class), and a test. A new field needs a value in every `new Stage(...)` (or `new GameConfig(...)`): the compiler lists them, in `game()` and the tests.
- Every piece of state you will want to check or skip to while testing gets a `/game` subcommand in `GameCommand.handle`: `game wave 5`, `game boss`, `game score Steve`. Return replies; never broadcast, so console callers see them.
- Log every state change on one line (`Game.moveTo` logs `Steve -> stage 2/3 arena (goal)`), so tests and tools can wait for it in the log.
- Build the stages in the world first, then copy their coordinates into `GameConfig.game()`. The flat world's standing height is y -60.
- If MSC tools are available: `server_command "game status"` shows the state and `server_command "game stage arena Steve"` skips ahead. Minestom has no `/tick`; drive timers from the scheduler and add a `/game` subcommand to skip them.

## Minestom is not vanilla

- Minestom is a library. Nothing happens unless code here makes it happen: no vanilla commands (`/tp`, `/give`, `/time`...; the agent adds a dev-only `/tp` for the test player while msc-core runs the server), no mob AI, no block physics, no item drops, no redstone. Write what the game needs.
- The world is generated by `createWorld`; `main` keeps it in `run/world` with an `AnvilLoader` and saves it on `stop`, so builds survive `dev_cycle`. A crash loses changes since the last stop: call `world.saveChunksToStorage()` after big changes. Delete `run/world` to regenerate.
- Events: `events.addListener(Event.class, e -> ...)` in `GameEvents`, or a child `EventNode` per feature.
- Commands: subclass `Command`, add syntaxes with `ArgumentType`, register with `MinecraftServer.getCommandManager().register(...)`.
- Blocks: `Block.STONE`, `Block.OAK_STAIRS.withProperty("facing", "north")`, `instance.setBlock(x, y, z, block)`. Chunks must be loaded first (`instance.loadChunk(cx, cz)`).
- Text is Adventure `Component`s (`Component.text("Hi", NamedTextColor.GREEN)`). Never use `§` codes.
- Threads: game state is touched only on the tick thread. Event listeners and player commands run there, and so do console commands: `Console` and msc's `server_command` hand each command to the scheduler, which runs it at the start of the next tick. So `Game` uses plain collections, and a step like "read the stage, then advance" never interleaves with a `/game` command. A concurrent collection would not give that: it protects one call, not the steps between calls.
- Code on any other thread (a virtual thread, a `CompletableFuture` callback, an `Async...` event) hops back before it touches game state: `MinecraftServer.getSchedulerManager().execute(() -> ...)` or `world.scheduleNextTick(w -> ...)`. `Game.confineTo` makes a change from another thread throw; `main` turns it on for the tick threads and `GameTest` for the test thread. `Progress` is the one exception: it is written to disk on a virtual thread, so it is thread-safe.
- Do not block the tick thread (no files, network or sleeps in listeners or commands): do slow work on a virtual thread, then hop back with its result.

## Tests

- Every change comes with a test. Pure logic: plain JUnit + AssertJ. Worlds, players, events, commands: Minestom's test environment.
- Pattern (see `GameTest`): annotate the class with `@EnvTest` and take `Env env` as a test parameter. Build the world with `createWorld(env.process().instance())` and load the chunks you use; register `GameEvents` on `env.process().eventHandler()`; connect a named player with `env.createConnection(new GameProfile(UUID.randomUUID(), "Steve")).connect(world, pos)`; fire events with `env.process().eventHandler().call(...)`; `env.tick()` advances one tick.
- Compare messages as plain text (`PlainTextComponentSerializer`), not by component equality.

## Minecraft Server Creator

- `MscAgent.start()` in `main` lets the AI's world tools (read, build, render, undo) work on the instance named `"world"`.
- `server_command` runs commands this server registered (`game ...`); there are no vanilla commands unless you add them.
- No Mojang server jar and no EULA step: Minestom implements the protocol itself. Players still need a Minecraft {{minestomMcVersion}} client.
