# {{name}}

Fabric server mod for Minecraft {{mcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL with Fabric Loom. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile and run all tests. Run it after every change; it must pass before you stop.
- `./gradlew test --tests '*GreeterTest'`: one test class.
- `./gradlew runServer`: start the dev server in `run/` with this mod and Fabric API loaded. Stop it with `stop` on the console or over RCON.
- Mod jar: `build/libs/{{projectId}}-{{version}}.jar`. A server needs Fabric Loader (or Quilt Loader, which runs Fabric mods) and Fabric API installed next to it.

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
- Config files go in `FabricLoader.getInstance().getConfigDir()`. Fabric has no config API; the recipe below reads a properties file.
- Prefer small final classes and records. Pass dependencies through constructors; avoid static state.

## Standard library (msc-stdlib)

The project depends on MSC's standard library (`libs.msc.stdlib.fabric`, shipped in `libs/repo/` with its sources, nested in the mod jar). Use it before writing your own. `Stdlib.init()` runs first in `onInitializeServer` (the template does it); `Stdlib.server()` is the running server.

Find what you need without reading sources: `grep -n "Gui" libs/stdlib/API.md` (one line per public method, with what it does), `graphify query "menu with a confirm button" --graph libs/stdlib/graphify-out/graph.json`, then read only the lines you need in `libs/stdlib/src/`. Packages are under `io.github.imnotstable.msc.stdlib`.

- Menus (server-side, vanilla clients see a chest): `Gui.chest(rows, title)` with `set(slot, item, click -> ...)`, `fill`, `border`, `editable`, `onClose`, `open(player)`; `Gui.paged(rows, title, list, icon, (entry, click) -> ...)`. Items cannot be taken out except from `editable` slots.
- Items: `ItemBuilder.of(Items.DIAMOND)` with `name`, `lore`, `glow`, `unbreakable`, `model`, `data(key, value)`, `with(component, value)`; `ItemBuilder.read(item, key)`, `ItemBuilder.filler()`, `ItemBuilder.text("Hi", ChatFormatting.GREEN)`.
- Particles: `ParticleEffect.of(ParticleTypes.FLAME)` or `ParticleEffect.dust(0xFF0000, size)`, then `spawn(level, point)`, `draw(level, origin, points)` or `play(scheduler, level, origin, period, frames, frame -> points)`. Shapes in `Shapes` (`circle`, `sphere`, `helix`, `star`, `cuboidEdges`, ...); `Locations.vec(player.position())` and `Locations.facing(player, points)`.
- Databases: `Database.open(DatabaseConfig.sqlite(path))` once `include(libs.sqlite.jdbc)` is uncommented in `build.gradle.kts` (tests already have the driver); for MySQL, MariaDB or PostgreSQL add their driver the same way and use `DatabaseConfig.server(...)`. `update`, `insert`, `query`, `queryOne`, `transaction`, `async(db -> ...)`; `Table.named(...)` schemas, `db.migrate(...)` with `Migration.of(...)`, `Store.sql(db, table, Codec.json(Account.class))` records by key, `Store.memory()` in tests.
- Player data: `PlayerData.create(store, defaults)` (in `onInitializeServer`) loads each player's record when they join, saves it when they leave and when the server stops; `get`, `update`, `saveAll`.
- Shared with every platform (see `libs/stdlib/API.md`): `Repository` records with no SQL, `Store` (`jsonFiles`, `cached`, `writeBehind`), `Cache`, `Economy`, `Config`, `Messenger`, `Redis`/`Mongo` (add their driver first), `Format`, `RateLimiter`, `Leaderboard`, `EventBus`, `Sequence`, `Cuboid`/`Region`, `Randoms`.
- Time: `Cooldowns` (`tryUse`, `remaining`), `Durations.parse("1h30m")`, `Durations.format`, `Stdlib.scheduler()` (`later`, `repeat`, `countdown`, `supplyAsync`). Loot: `WeightedRandom`.
- Tests that make items need Minecraft's item components: after `Bootstrap.bootStrap()`, run `BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup()).forEach(DataComponentInitializers.PendingComponents::apply)` once.

## Recipes

Built and tested against this project's pinned versions; copy them as they are.

A command with an argument, `src/main/java/{{packagePath}}/CountdownCommand.java`:

```java
package {{packageName}};

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /countdown <seconds>}: a command with a number argument, 1 to 600. */
public final class CountdownCommand {
    private CountdownCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("countdown")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600)).executes(context -> {
                    int seconds = IntegerArgumentType.getInteger(context, "seconds");
                    context.getSource().sendSuccess(() -> Component.literal("Starting in " + seconds + "s"), false);
                    return seconds;
                })));
    }
}
```

A setting, `src/main/java/{{packagePath}}/Settings.java`:

```java
package {{packageName}};

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The mod's settings, in config/{{projectId}}.properties, written with the defaults on first start. */
public record Settings(boolean protectBlocks) {
    static Settings load(Path file) {
        Properties p = new Properties();
        p.setProperty("protect-blocks", "false");
        try {
            if (Files.exists(file)) {
                try (Reader in = Files.newBufferedReader(file)) {
                    p.load(in);
                }
            } else {
                try (Writer out = Files.newBufferedWriter(file)) {
                    p.store(out, "{{name}} settings");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Settings(Boolean.parseBoolean(p.getProperty("protect-blocks")));
    }
}
```

The entry point with both wired in, and a block-break listener that reads the setting:

```java
package {{packageName}};

import io.github.imnotstable.msc.stdlib.fabric.Stdlib;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod entry point: reads settings, registers commands and event handlers, and holds no game logic itself. */
public final class {{mainClass}} implements DedicatedServerModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("{{projectId}}");

    @Override
    public void onInitializeServer() {
        Stdlib.init();
        Greeter greeter = new Greeter(Greeter.DEFAULT_JOIN, Greeter.DEFAULT_HELLO);
        Settings settings = Settings.load(FabricLoader.getInstance().getConfigDir().resolve("{{projectId}}.properties"));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            HelloCommand.register(dispatcher, greeter);
            CountdownCommand.register(dispatcher);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                handler.getPlayer().sendSystemMessage(greeter.join(handler.getPlayer().getPlainTextName())));
        // Returning false stops the break.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !settings.protectBlocks());
        LOG.info("{{name}} loaded");
    }
}
```

## Tests

- Every change comes with a test. Pure logic: plain JUnit + AssertJ. Commands: register into a fresh `CommandDispatcher<CommandSourceStack>` and execute with a recording `CommandSource`, as `HelloCommandTest` does.
- Code that needs registries (blocks, items, and the `Commands` class) calls `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();` once in `@BeforeAll`.
- Compare messages as plain text (`component.getString()`), not by component equality.

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
