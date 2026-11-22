# {{name}}

Minecraft Forge server mod for Minecraft {{mcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL with ForgeGradle 7. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile and run all tests. Run it after every change; it must pass before you stop.
- `./gradlew test --tests '*GreeterTest'`: one test class.
- `./gradlew runServer`: start the dev server in `run/` with this mod loaded. Stop it with `stop` on the console or over RCON.
- Mod jar: `build/libs/{{projectId}}-{{version}}.jar`. A server needs Forge {{forgeVersion}} installed.

## Layout

- `src/main/java/{{packagePath}}/{{mainClass}}.java`: entry point (`@Mod`). Only wiring here (commands, event listeners); put logic in its own classes.
- `src/main/resources/META-INF/mods.toml`: mod metadata and dependencies. The mod id is `{{modId}}`.
- `src/main/resources/pack.mcmeta`: marks the jar as a resource and data pack. Forge needs it to load anything under `assets/` or `data/`; without it the server warns `Missing metadata in pack mod:{{modId}}`.
- `src/test/java/`: plain unit tests (`GreeterTest`). They run on plain JUnit without Forge loaded: keep logic that tests need free of registries, and check commands and events on the dev server.
- `gradle/libs.versions.toml`: every dependency version. Add versions there, never inline in `build.gradle.kts`.
- `run/`: dev server. `server.properties` and `eula.txt` are committed; the world, logs and jars are not.

## Minecraft Forge conventions

- Names are Mojang's official names (Minecraft 26.x is not obfuscated): `ServerPlayer`, `ServerLevel`, `BlockPos`, `Component`. Don't use SRG names (`m_12345_`) or names from old tutorials.
- This is a server-side mod: never reference client classes (`net.minecraft.client`).
- Each Forge event has its own bus: `ServerStartingEvent.BUS.addListener(e -> ...)`, `RegisterCommandsEvent.BUS`, `PlayerEvent.PlayerLoggedInEvent.BUS`, `TickEvent.ServerTickEvent.Post.BUS`, `BlockEvent.BreakEvent.BUS`. Add a mixin only when no event exists.
- A cancellable event's bus (`BlockEvent.BreakEvent.BUS`) has two one-argument `addListener` methods: a `Consumer` and a `Predicate` that returns `true` to cancel. A lambda whose body is a single boolean expression fits both and does not compile; give it a block body (`e -> { return ...; }`), as the recipe below does.
- Commands are Brigadier trees built with `Commands.literal(...)` and `Commands.argument(...)`; reply with `source.sendSuccess(() -> component, false)` or `source.sendFailure(component)`.
- Text is `Component.literal(...)` / `Component.translatable(...)` with `.withStyle(ChatFormatting.X)`. Never use `§` codes.
- Never block the server thread (no I/O, sleeps or network in listeners or commands). Do slow work on another thread, then hop back with `server.execute(...)` before touching the world.
- World, entity and inventory access only on the server thread.
- Config goes through `ForgeConfigSpec`: take `FMLJavaModLoadingContext context` in the constructor and call `context.registerConfig(ModConfig.Type.SERVER, spec)`. `SERVER` configs live in the world's `serverconfig/{{modId}}-server.toml` and load when the server starts, so read values (`value.get()`) in listeners and commands, not in the constructor.
- Prefer small final classes and records. Pass dependencies through constructors; avoid static state.

## Standard library (msc-stdlib)

The project depends on MSC's standard library (`libs.msc.stdlib.forge`, shipped in `libs/repo/` with its sources, nested in the mod jar with jar-in-jar). Use it before writing your own. `Stdlib.init()` runs first in your mod's constructor (the template does it); `Stdlib.server()` is the running server.

Find what you need without reading sources: `grep -n "Gui" libs/stdlib/API.md` (one line per public method, with what it does), `graphify query "menu with a confirm button" --graph libs/stdlib/graphify-out/graph.json`, then read only the lines you need in `libs/stdlib/src/`. Packages are under `io.github.imnotstable.msc.stdlib`.

- Menus (server-side, vanilla clients see a chest): `Gui.chest(rows, title)` with `set(slot, item, click -> ...)`, `fill`, `border`, `editable`, `onClose`, `open(player)`; `Gui.paged(rows, title, list, icon, (entry, click) -> ...)`. Items cannot be taken out except from `editable` slots.
- Items: `ItemBuilder.of(Items.DIAMOND)` with `name`, `lore`, `glow`, `unbreakable`, `model`, `data(key, value)`, `with(component, value)`; `ItemBuilder.read(item, key)`, `ItemBuilder.filler()`, `ItemBuilder.text("Hi", ChatFormatting.GREEN)`.
- Particles: `ParticleEffect.of(ParticleTypes.FLAME)` or `ParticleEffect.dust(0xFF0000, size)`, then `spawn(level, point)`, `draw(level, origin, points)` or `play(scheduler, level, origin, period, frames, frame -> points)`. Shapes in `Shapes` (`circle`, `sphere`, `helix`, `star`, `cuboidEdges`, ...); `Locations.vec(player.position())` and `Locations.facing(player, points)`.
- Databases: `Database.open(DatabaseConfig.sqlite(path))` once `nested(libs.sqlite.jdbc)` is uncommented in `build.gradle.kts` (tests already have the driver); for MySQL, MariaDB or PostgreSQL add their driver the same way and use `DatabaseConfig.server(...)`. `update`, `insert`, `query`, `queryOne`, `transaction`, `async(db -> ...)`; `Table.named(...)` schemas, `db.migrate(...)` with `Migration.of(...)`, `Store.sql(db, table, Codec.json(Account.class))` records by key, `Store.memory()` in tests.
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

import net.minecraftforge.common.ForgeConfigSpec;

/** The mod's settings, in the world's serverconfig/{{modId}}-server.toml. */
public final class Settings {
    final ForgeConfigSpec.BooleanValue protectBlocks;
    final ForgeConfigSpec spec;

    Settings() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        protectBlocks = builder.comment("Stop players breaking blocks.").define("protectBlocks", false);
        spec = builder.build();
    }
}
```

The entry point with both wired in, and a block-break listener that reads the setting:

```java
package {{packageName}};

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod entry point: registers settings, commands and event listeners, and holds no game logic itself. */
@Mod({{mainClass}}.MOD_ID)
public final class {{mainClass}} {
    public static final String MOD_ID = "{{modId}}";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public {{mainClass}}(FMLJavaModLoadingContext context) {
        Greeter greeter = new Greeter(Greeter.DEFAULT_JOIN, Greeter.DEFAULT_HELLO);
        Settings settings = new Settings();
        context.registerConfig(ModConfig.Type.SERVER, settings.spec);

        RegisterCommandsEvent.BUS.addListener(e -> {
            HelloCommand.register(e.getDispatcher(), greeter);
            CountdownCommand.register(e.getDispatcher());
        });
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(e ->
                e.getEntity().sendSystemMessage(greeter.join(e.getEntity().getPlainTextName())));
        // Returning true cancels the break.
        BlockEvent.BreakEvent.BUS.addListener(e -> {
            return settings.protectBlocks.get();
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
