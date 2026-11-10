# {{name}}

Paper plugin for Minecraft {{mcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile and run all tests. Run it after every change; it must pass before you stop.
- `./gradlew test --tests '*GreeterTest'`: one test class.
- `./gradlew runServer`: start the dev server in `run/` with this plugin loaded. Stop it with `stop` on the console or over RCON.
- Plugin jar: `build/libs/{{projectId}}-{{version}}.jar`.

## Layout

- `src/main/java/{{packagePath}}/{{mainClass}}.java`: entry point. Only wiring here (config, listeners, commands); put logic in its own classes.
- `src/main/resources/plugin.yml`: plugin metadata. `config.yml`: defaults, copied to `run/plugins/{{name}}/` on first start.
- `src/test/java/`: plain unit tests (`GreeterTest`) and MockBukkit tests (`{{mainClass}}Test`).
- `gradle/libs.versions.toml`: every dependency version. Add versions there, never inline in `build.gradle.kts`.
- `run/`: dev server. `server.properties` and `eula.txt` are committed; the world, logs and jars are not.

## Paper conventions

- Text is Adventure `Component`s, written as MiniMessage (`<green>Hi <player>`). Never use `ChatColor` or `§` codes. Insert player input with `Placeholder.unparsed`.
- Commands use Paper's Brigadier API: register in `onEnable` via `getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)`. Use `BasicCommand` for simple commands, `Commands.literal(...)` trees for arguments. Do not add commands to `plugin.yml`.
- Never block the main thread (no I/O, sleeps or network in event handlers or commands). Use `getServer().getScheduler().runTaskAsynchronously(...)`, then hop back with `runTask(...)` before touching the world.
- World, entity and inventory access only on the main thread.
- Read settings from `getConfig()`; add every new key with a default to `config.yml`.
- Prefer small final classes and records. Pass dependencies through constructors; avoid static state so MockBukkit tests stay isolated.

## Tests

- Every change comes with a test. Pure logic: plain JUnit + AssertJ. Events, commands, players: MockBukkit.
- MockBukkit pattern: `MockBukkit.mock()` and `MockBukkit.load({{mainClass}}.class)` in `@BeforeEach`, `MockBukkit.unmock()` in `@AfterEach`; `server.addPlayer("Steve")` fires a join; `player.performCommand("hello")` runs a command; read replies with `player.nextComponentMessage()`.
- Compare messages as plain text (`PlainTextComponentSerializer`), not by component equality.

## Dev server

- Offline mode, bound to {{serverIp}}:{{serverPort}}, flat creative world, peaceful, no nether, no structures.
- RCON on port {{rconPort}}; the password is `rcon.password` in `run/server.properties`.
- If Minecraft Server Creator tools are available, use them to start the server, read condensed logs and inspect the world instead of reading raw `run/logs/latest.log`.
