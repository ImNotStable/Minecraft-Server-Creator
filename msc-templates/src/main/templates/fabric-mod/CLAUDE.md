# {{name}}

Fabric server mod for Minecraft {{mcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL with Fabric Loom. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile and run all tests. Run it after every change; it must pass before you stop.
- `./gradlew test --tests '*GreeterTest'`: one test class.
- `./gradlew runServer`: start the dev server in `run/` with this mod and Fabric API loaded. Stop it with `stop` on the console or over RCON.
- Mod jar: `build/libs/{{projectId}}-{{version}}.jar`. A server needs Fabric Loader and Fabric API installed next to it.

## Layout

- `src/main/java/{{packagePath}}/{{mainClass}}.java`: entry point (`DedicatedServerModInitializer`). Only wiring here (commands, event callbacks); put logic in its own classes.
- `src/main/resources/fabric.mod.json`: mod metadata, entry points and dependencies.
- `src/test/java/`: plain unit tests (`GreeterTest`) and command tests that run the real Brigadier tree (`HelloCommandTest`). Fabric Loader runs inside the test JVM (`fabric-loader-junit`), so Minecraft and Fabric API classes work in tests.
- `gradle/libs.versions.toml`: every dependency version. Add versions there, never inline in `build.gradle.kts`.
- `run/`: dev server. `server.properties` and `eula.txt` are committed; the world, logs and jars are not.

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

- Every change comes with a test. Pure logic: plain JUnit + AssertJ. Commands: register into a fresh `CommandDispatcher<CommandSourceStack>` and execute with a recording `CommandSource`, as `HelloCommandTest` does.
- Code that needs registries (blocks, items, and the `Commands` class) calls `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();` once in `@BeforeAll`.
- Compare messages as plain text (`component.getString()`), not by component equality.

## Dev server

- Offline mode, bound to {{serverIp}}:{{serverPort}}, flat creative world, peaceful, no nether, no structures.
- `run/eula.txt` must say `eula=true` before the server starts. Accepting the Minecraft EULA (https://aka.ms/MinecraftEULA) is the owner's decision: if it says `false`, ask them, never change it yourself.
- RCON on port {{rconPort}}; the password is `rcon.password` in `run/server.properties`.
- If Minecraft Server Creator tools are available, use them to start the server, read condensed logs and inspect the world instead of reading raw `run/logs/latest.log`.
