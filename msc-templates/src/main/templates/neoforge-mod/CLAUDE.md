# {{name}}

NeoForge server mod for Minecraft {{mcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL with NeoForge ModDevGradle. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile and run all tests. Run it after every change; it must pass before you stop.
- `./gradlew test --tests '*GreeterTest'`: one test class.
- `./gradlew runServer`: start the dev server in `run/` with this mod loaded. Stop it with `stop` on the console or over RCON.
- Mod jar: `build/libs/{{projectId}}-{{version}}.jar`. A server needs NeoForge {{neoforgeVersion}} installed.

## Layout

- `src/main/java/{{packagePath}}/{{mainClass}}.java`: entry point (`@Mod`). Only wiring here (commands, event listeners); put logic in its own classes.
- `src/main/resources/META-INF/neoforge.mods.toml`: mod metadata and dependencies. The mod id is `{{modId}}`.
- `src/test/java/`: plain unit tests (`GreeterTest`) and command tests that run the real Brigadier tree (`HelloCommandTest`). Tests run inside NeoForge (ModDevGradle `unitTest`), so Minecraft and NeoForge classes work in them without any bootstrap.
- `gradle/libs.versions.toml`: every dependency version. Add versions there, never inline in `build.gradle.kts`.
- `run/`: dev server. `server.properties` and `eula.txt` are committed; the world, logs and jars are not.

## NeoForge conventions

- Names are Mojang's official names (Minecraft 26.x is not obfuscated): `ServerPlayer`, `ServerLevel`, `BlockPos`, `Component`. Don't use SRG names (`m_12345_`) or names from old tutorials.
- This is a server-side mod: never reference client classes (`net.minecraft.client`).
- Listen on `NeoForge.EVENT_BUS` with `addListener(EventClass.class, e -> ...)`: `RegisterCommandsEvent`, `PlayerEvent.PlayerLoggedInEvent`, `ServerStartingEvent`, `ServerTickEvent.Post`, `BlockEvent.BreakEvent`, `PlayerInteractEvent.RightClickBlock`. Add a mixin only when no event exists.
- Commands are Brigadier trees built with `Commands.literal(...)` and `Commands.argument(...)`; reply with `source.sendSuccess(() -> component, false)` or `source.sendFailure(component)`.
- Text is `Component.literal(...)` / `Component.translatable(...)` with `.withStyle(ChatFormatting.X)`. Never use `§` codes.
- Never block the server thread (no I/O, sleeps or network in listeners or commands). Do slow work on another thread, then hop back with `server.execute(...)` before touching the world.
- World, entity and inventory access only on the server thread.
- Config goes through `ModConfigSpec`, registered in the constructor with `ModContainer.registerConfig`.
- Prefer small final classes and records. Pass dependencies through constructors; avoid static state.

## Tests

- Every change comes with a test. Pure logic: plain JUnit + AssertJ. Compare messages as plain text (`component.getString()`), not by component equality.

## Dev server

- Offline mode, bound to {{serverIp}}:{{serverPort}}, flat creative world, peaceful, no nether, no structures.
- `run/eula.txt` must say `eula=true` before the server starts. Accepting the Minecraft EULA (https://aka.ms/MinecraftEULA) is the owner's decision: if it says `false`, ask them, never change it yourself.
- RCON on port {{rconPort}}; the password is `rcon.password` in `run/server.properties`.
- If Minecraft Server Creator tools are available, use them to start the server, read condensed logs and inspect the world instead of reading raw `run/logs/latest.log`.

## Testing a game

- The dev server is creative and peaceful. For combat, damage or hunger, switch it with the MSC tool `server_setup mode=game` (survival, normal difficulty); the start report says which mode is on.
- Chunks with no player nearby are not loaded, so nothing in them moves: mobs spawned there freeze. Keep game areas loaded with `level.setChunkForced(chunkX, chunkZ, true)` or `forceload add x1 z1 x2 z2`. Spawner blocks and natural spawning also need a player close by, so spawn waves from code.
- `tick sprint 600` on the console runs 600 ticks (30 game seconds) at full speed, so timers and waves come sooner.
- Log what the game does (stage started, wave cleared, player won) so tools can wait for it (`server_wait until=...`) and check it (`world_entities`), and add admin commands that jump to any stage.
