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
- Listen on `NeoForge.EVENT_BUS` with `addListener(EventClass.class, e -> ...)`: `RegisterCommandsEvent`, `PlayerEvent.PlayerLoggedInEvent`, `ServerStartingEvent`, `ServerTickEvent.Post`, `BreakBlockEvent` (`net.neoforged.neoforge.event.level.block`), `PlayerInteractEvent.RightClickBlock`. Add a mixin only when no event exists.
- Commands are Brigadier trees built with `Commands.literal(...)` and `Commands.argument(...)`; reply with `source.sendSuccess(() -> component, false)` or `source.sendFailure(component)`.
- Text is `Component.literal(...)` / `Component.translatable(...)` with `.withStyle(ChatFormatting.X)`. Never use `§` codes.
- Never block the server thread (no I/O, sleeps or network in listeners or commands). Do slow work on another thread, then hop back with `server.execute(...)` before touching the world.
- World, entity and inventory access only on the server thread.
- Config goes through `ModConfigSpec`: take `ModContainer container` in the constructor and call `container.registerConfig(ModConfig.Type.LOCAL, spec)`. `LOCAL` is the type for a server-side mod's settings (`config/{{modId}}-local.toml`, not synced); NeoForge 26.x has no `COMMON` or `SERVER` type, and `SYNCED` is sent to clients, which do not have this mod. Read a value (`value.getAsBoolean()`) in listeners and commands, not in the constructor: only `STARTUP` configs are loaded that early.
- Prefer small final classes and records. Pass dependencies through constructors; avoid static state.

## Standard library (msc-stdlib)

The project depends on MSC's standard library (`libs.msc.stdlib.neoforge`, shipped in `libs/repo/` with its sources, nested in the mod jar with jar-in-jar). Use it before writing your own. `Stdlib.init()` runs first in your mod's constructor (the template does it); `Stdlib.server()` is the running server.

Find what you need without reading sources: `grep -n "Gui" libs/stdlib/API.md` (one line per public method, with what it does), `graphify query "menu with a confirm button" --graph libs/stdlib/graphify-out/graph.json`, then read only the lines you need in `libs/stdlib/src/`. Packages are under `io.github.imnotstable.msc.stdlib`.

- Menus (server-side, vanilla clients see a chest): `Gui.chest(rows, title)` with `set(slot, item, click -> ...)`, `fill`, `border`, `editable`, `onClose`, `open(player)`; `Gui.paged(rows, title, list, icon, (entry, click) -> ...)`. Items cannot be taken out except from `editable` slots.
- Items: `ItemBuilder.of(Items.DIAMOND)` with `name`, `lore`, `glow`, `unbreakable`, `model`, `data(key, value)`, `with(component, value)`; `ItemBuilder.read(item, key)`, `ItemBuilder.filler()`, `ItemBuilder.text("Hi", ChatFormatting.GREEN)`.
- Particles: `ParticleEffect.of(ParticleTypes.FLAME)` or `ParticleEffect.dust(0xFF0000, size)`, then `spawn(level, point)`, `draw(level, origin, points)` or `play(scheduler, level, origin, period, frames, frame -> points)`. Shapes in `Shapes` (`circle`, `sphere`, `helix`, `star`, `cuboidEdges`, ...); `Locations.vec(player.position())` and `Locations.facing(player, points)`.
- Databases: `Database.open(DatabaseConfig.sqlite(path))` `jarJar(libs.sqlite.jdbc)` is uncommented in `build.gradle.kts` (tests already have the driver); for MySQL, MariaDB or PostgreSQL add their driver the same way and use `DatabaseConfig.server(...)`. `update`, `insert`, `query`, `queryOne`, `transaction`, `async(db -> ...)`; `Table.named(...)` schemas, `db.migrate(...)` with `Migration.of(...)`, `Store.sql(db, table, Codec.json(Account.class))` records by key, `Store.memory()` in tests.
- Player data: `PlayerData.create(store, defaults)` (in the constructor) loads each player's record when they join, saves it when they leave and when the server stops; `get`, `update`, `saveAll`.
- Players see: `Feedback` (`success`, `fail`, `info`, `actionBar`, `title`), `Sounds` presets (`Sounds.SUCCESS.play(player)`), `Bar` boss bars (`Bar.create`, `Bar.timer`), `Hologram` text displays. Gameplay: `CustomItems.register(id, factory)` (`onUse`, `onHit`, `cooldown`), `Regions.define(name, Level.OVERWORLD, region)` (`noBuild`, `noPvp`, `onEnter`), `Prompt.ask`, `Teleports.warmup`, `Players` (`give`, `heal`, `reset`), `Lang` (placeholders filled, no tags on Fabric).
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

import net.neoforged.neoforge.common.ModConfigSpec;

/** The mod's settings, in config/{{modId}}-local.toml. */
public final class Settings {
    final ModConfigSpec.BooleanValue protectBlocks;
    final ModConfigSpec spec;

    Settings() {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        protectBlocks = builder.comment("Stop players breaking blocks.").define("protectBlocks", false);
        spec = builder.build();
    }
}
```

The entry point with both wired in, and a block-break listener that reads the setting:

```java
package {{packageName}};

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod entry point: registers settings, commands and event handlers, and holds no game logic itself. */
@Mod(value = {{mainClass}}.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class {{mainClass}} {
    public static final String MOD_ID = "{{modId}}";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public {{mainClass}}(ModContainer container) {
        Greeter greeter = new Greeter(Greeter.DEFAULT_JOIN, Greeter.DEFAULT_HELLO);
        Settings settings = new Settings();
        container.registerConfig(ModConfig.Type.LOCAL, settings.spec);

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, e -> {
            HelloCommand.register(e.getDispatcher(), greeter);
            CountdownCommand.register(e.getDispatcher());
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e ->
                e.getEntity().sendSystemMessage(greeter.join(e.getEntity().getPlainTextName())));
        NeoForge.EVENT_BUS.addListener(BreakBlockEvent.class, e -> {
            if (settings.protectBlocks.getAsBoolean()) {
                e.setCanceled(true);
            }
        });
        LOG.info("{{name}} loaded");
    }
}
```

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
