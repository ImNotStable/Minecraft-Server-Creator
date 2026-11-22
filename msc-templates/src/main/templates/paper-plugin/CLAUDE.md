# {{name}}

Paper plugin for Minecraft {{mcVersion}}, Java {{javaVersion}}, Gradle Kotlin DSL. Scaffolded by Minecraft Server Creator.

## Commands

- `./gradlew build`: compile and run all tests. Run it after every change; it must pass before you stop.
- `./gradlew test --tests '*GreeterTest'`: one test class.
- `./gradlew runServer`: start the dev server in `run/` with this plugin loaded. Stop it with `stop` on the console or over RCON.
- Plugin jar: `build/libs/{{projectId}}-{{version}}.jar`.

## Layout

- `src/main/java/{{packagePath}}/{{mainClass}}.java`: entry point. Only wiring here (config, listeners, commands); put logic in its own classes.
- `build.gradle.kts`, block `bukkitPluginYaml { }`: plugin metadata (name, main class, api-version, authors, permissions, dependencies with `depend.add("LuckPerms")`); resource-factory writes `plugin.yml` from it at build time and fails the build on a bad value. Do not add a `plugin.yml` file. `src/main/resources/config.yml`: defaults, copied to `run/plugins/{{name}}/` on first start.
- `src/test/java/`: plain unit tests (`GreeterTest`) and MockBukkit tests (`{{mainClass}}Test`).
- `gradle/libs.versions.toml`: every dependency version. Add versions there, never inline in `build.gradle.kts`.
- `run/`: dev server. `server.properties` and `eula.txt` are committed; the world, logs and jars are not.

## Paper conventions

- Text is Adventure `Component`s, written as MiniMessage (`<green>Hi <player>`). Never use `ChatColor` or `§` codes. Insert player input with `Placeholder.unparsed`.
- Commands use Paper's Brigadier API: register in `onEnable` via `getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, ...)`. Use `BasicCommand` for simple commands, `Commands.literal(...)` trees for arguments. Do not add commands to `bukkitPluginYaml`.
- Never block the main thread (no I/O, sleeps or network in event handlers or commands). Use `getServer().getScheduler().runTaskAsynchronously(...)`, then hop back with `runTask(...)` before touching the world.
- World, entity and inventory access only on the main thread.
- Read settings from `getConfig()`; add every new key with a default to `config.yml`.
- Minecraft internals (NMS) are on the compile classpath through paperweight-userdev, Mojang-named (`net.minecraft.*`, `org.bukkit.craftbukkit.*`): reach them with `((CraftPlayer) player).getHandle()`, `((CraftWorld) world).getHandle()`, `((CraftEntity) entity).getHandle()`. Use the Paper API first; NMS for what it lacks (packets, custom entity AI goals, registries, world generation). Keep NMS calls in their own classes: MockBukkit has no server internals, so a test that reaches them fails with `NoClassDefFoundError`; check that code on the dev server (`dev_cycle`, `player_test`). NMS changes between Minecraft versions, so it ties the plugin to this one. Look classes up with the MSC tools `api_search` and `api_class` after the first build.
- Prefer small final classes and records, except the main plugin class: MockBukkit must subclass it, so never make it final. Pass dependencies through constructors; avoid static state so MockBukkit tests stay isolated.

## Standard library (msc-stdlib)

The project depends on MSC's standard library (`libs.msc.stdlib.paper`, shipped in `libs/repo/`, bundled into the plugin jar). Use it before writing your own, and call `Stdlib.init(this)` first in `onEnable` (the template does). Packages under `io.github.imnotstable.msc.stdlib`; its sources are in `libs/repo/` next to each jar.

- Menus: `Gui.chest(rows, title)` with `set(slot, item, click -> ...)`, `fill`, `border`, `editable`, `onClose`; `Gui.paged(rows, title, list, icon, (entry, click) -> ...)` pages a list. Items cannot be taken out except from `editable` slots.
- Items: `ItemBuilder.of(Material)` with `name`, `lore`, `enchant`, `glow`, `unbreakable`, `model`, `data(key, type, value)`, `meta(...)`; `ItemBuilder.read` gets the data back; `ItemBuilder.head(player)`, `ItemBuilder.filler()`.
- Text: `Text.mm("<green>Hi <name>", "name", value)` (values are inserted as plain text, `Component`s as they are), `Text.plain`, `Text.legacy`.
- Particles: `ParticleEffect.of(Particle.FLAME)` or `ParticleEffect.dust(color, size)`, then `spawn`, `draw(origin, points)` or `play(scheduler, origin, period, frames, frame -> points)`. Shapes in `Shapes`: `line`, `circle`, `arc`, `disc`, `sphere`, `helix`, `cuboidEdges`, `star`, `randomInSphere`, turned with `rotateY` and `translate`; `Locations.facing` turns them to where a player looks.
- Databases: `Database.open(PaperDatabase.config(getConfig().getConfigurationSection("database"), getDataFolder().toPath()))` picks SQLite (default, a file in the plugin folder), MySQL, MariaDB or PostgreSQL from `config.yml`. `update`, `insert` (returns the id), `query`, `queryOne`, `batch`, `transaction`, and `async(db -> ...)` to stay off the main thread. Schemas: `Table.named(...).key(...).column(...).sql(db.type())` writes SQL every backend accepts; `db.migrate("{{projectId}}", List.of(Migration.of(1, "...", sql)))` applies each version once. `Store.sql(db, table, Codec.json(Account.class))` keeps records by key without writing SQL; `Store.memory()` for tests. Close the database in `onDisable`.
- Player data: `PlayerData.create(store, defaults)` loads each player's record at login, keeps it while they play, saves it on quit; `get`, `update`, `saveAll` (call in `onDisable`).
- Time: `Cooldowns` (`tryUse(key, duration)`, `remaining`), `Durations.parse("1h30m")`, `Durations.format`, `Stdlib.scheduler()` (`later`, `repeat`, `countdown`, `supplyAsync` that comes back on the main thread). Scoreboard: `Sidebar` (`lines`, `line`, `show`, `hide`). Loot and chances: `WeightedRandom`.
- MockBukkit does not implement scoreboard number formats, so a test that makes a `Sidebar` is skipped there; test what fills its lines instead. SQLite works in tests (the driver is on the test classpath).

## Recipes

Built and tested against this project's pinned versions; copy them as they are.

A command with an argument, `src/main/java/{{packagePath}}/CountdownCommand.java`:

```java
package {{packageName}};

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/** {@code /countdown <seconds>}: a command with a number argument, 1 to 600. */
public final class CountdownCommand {
    private CountdownCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal("countdown")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600)).executes(context -> {
                    int seconds = IntegerArgumentType.getInteger(context, "seconds");
                    context.getSource().getSender().sendMessage(MiniMessage.miniMessage().deserialize(
                            "<yellow>Starting in <seconds>s", Placeholder.unparsed("seconds", String.valueOf(seconds))));
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }
}
```

Minecraft internals kept in one class, `src/main/java/{{packagePath}}/Nms.java` (call it from a command or listener; no MockBukkit test may reach it):

```java
package {{packageName}};

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/** The NMS calls of this plugin. MockBukkit has no server internals, so only the dev server runs this. */
public final class Nms {
    private Nms() {
    }

    /** The player's latency in milliseconds as the server measured it, read from its connection. */
    public static int latency(Player player) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        return handle.connection.latency();
    }
}
```

A block-break listener, `src/main/java/{{packagePath}}/BlockGuard.java`:

```java
package {{packageName}};

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/** Stops players breaking blocks. */
public final class BlockGuard implements Listener {
    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        event.setCancelled(true);
    }
}
```

The entry point with both wired in, the listener only while the setting `protect-blocks` is on (add `protect-blocks: false` to `config.yml`), and the shop below with its database:

```java
package {{packageName}};

import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.paper.PaperDatabase;
import io.github.imnotstable.msc.stdlib.paper.Stdlib;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point: wires config, listeners and commands, and holds no game logic itself.
 * Not final: MockBukkit subclasses the main class when loading it in tests.
 */
public class {{mainClass}} extends JavaPlugin {
    private Database database;
    private Wallet wallet;

    @Override
    public void onEnable() {
        Stdlib.init(this);
        saveDefaultConfig();
        Greeter greeter = new Greeter(
                getConfig().getString("join-message", Greeter.DEFAULT_JOIN),
                getConfig().getString("hello-message", Greeter.DEFAULT_HELLO));
        database = Database.open(PaperDatabase.config(getConfig().getConfigurationSection("database"),
                getDataFolder().toPath()));
        wallet = new Wallet(database);
        ShopMenu shop = new ShopMenu(wallet);

        getServer().getPluginManager().registerEvents(new JoinListener(greeter), this);
        if (getConfig().getBoolean("protect-blocks", false)) {
            getServer().getPluginManager().registerEvents(new BlockGuard(), this);
        }
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("hello", "Says hello to you.", new HelloCommand(greeter));
            event.registrar().register(CountdownCommand.node(), "Counts down.");
            event.registrar().register("shop", "Opens the shop.", (source, args) -> {
                if (source.getExecutor() instanceof Player player) {
                    shop.open(player);
                }
            });
        });
    }

    @Override
    public void onDisable() {
        if (wallet != null) {
            wallet.saveAll();
        }
        if (database != null) {
            database.close();
        }
    }

    public Wallet wallet() {
        return wallet;
    }
}
```

Coins per player, kept in the database, and a log of purchases in a table of its own, `src/main/java/{{packagePath}}/Wallet.java`:

```java
package {{packageName}};

import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.ColumnType;
import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.Migration;
import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.db.Table;
import io.github.imnotstable.msc.stdlib.paper.PlayerData;
import java.time.Instant;
import java.util.List;
import org.bukkit.entity.Player;

/** Each player's coins (100 to start) and every purchase. */
public final class Wallet {
    public static final int DIAMOND_PRICE = 50;

    public record Account(int coins) {
    }

    private final Database database;
    private final PlayerData<Account> accounts;

    public Wallet(Database database) {
        this.database = database;
        database.migrate("{{projectId}}", List.of(Migration.of(1, "purchase log",
                Table.named("purchases").id("id").column("player", ColumnType.UUID)
                        .column("item", ColumnType.STRING).column("at", ColumnType.INSTANT).sql(database.type()))));
        Store<Account> store = Store.sql(database, "accounts", Codec.json(Account.class));
        this.accounts = PlayerData.create(store, () -> new Account(100));
    }

    public int coins(Player player) {
        return accounts.get(player).coins();
    }

    /** Takes {@code price} coins and logs the purchase; false, and nothing taken, when the player has too few. */
    public boolean buy(Player player, String item, int price) {
        if (coins(player) < price) {
            return false;
        }
        accounts.update(player, a -> new Account(a.coins() - price));
        database.async(db -> db.insert("INSERT INTO purchases (player, item, at) VALUES (?, ?, ?)",
                player.getUniqueId(), item, Instant.now()));
        return true;
    }

    /** Blocks on the database: call it through {@code database.async} or from a command run off the main thread. */
    public int purchases(Player player) {
        return database.queryOne("SELECT COUNT(*) AS n FROM purchases WHERE player = ?", r -> r.integer("n"),
                player.getUniqueId()).orElse(0);
    }

    public void saveAll() {
        accounts.saveAll();
    }
}
```

A shop menu with a cooldown and particles, `src/main/java/{{packagePath}}/ShopMenu.java`:

```java
package {{packageName}};

import io.github.imnotstable.msc.stdlib.geom.Shapes;
import io.github.imnotstable.msc.stdlib.paper.Gui;
import io.github.imnotstable.msc.stdlib.paper.ItemBuilder;
import io.github.imnotstable.msc.stdlib.paper.ParticleEffect;
import io.github.imnotstable.msc.stdlib.text.Text;
import io.github.imnotstable.msc.stdlib.time.Cooldowns;
import io.github.imnotstable.msc.stdlib.time.Durations;
import java.time.Duration;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** {@code /shop}: one diamond for coins, at most once every 10 seconds. */
public final class ShopMenu {
    private final Wallet wallet;
    private final Cooldowns<UUID> cooldowns = new Cooldowns<>();

    public ShopMenu(Wallet wallet) {
        this.wallet = wallet;
    }

    public Gui open(Player player) {
        return Gui.chest(3, Text.mm("<gold>Shop"))
                .set(13, ItemBuilder.of(Material.DIAMOND).name("<aqua>Diamond")
                        .lore("<gray>Price: <price> coins", "price", Wallet.DIAMOND_PRICE).build(),
                        click -> buy(click.player()))
                .fill(ItemBuilder.filler())
                .open(player);
    }

    void buy(Player player) {
        UUID id = player.getUniqueId();
        if (!cooldowns.ready(id)) {
            player.sendMessage(Text.mm("<red>Wait <time>", "time", Durations.format(cooldowns.remaining(id))));
            return;
        }
        if (!wallet.buy(player, "diamond", Wallet.DIAMOND_PRICE)) {
            player.sendMessage(Text.mm("<red>You need <price> coins", "price", Wallet.DIAMOND_PRICE));
            return;
        }
        cooldowns.set(id, Duration.ofSeconds(10));
        player.getInventory().addItem(ItemStack.of(Material.DIAMOND));
        ParticleEffect.of(Particle.HAPPY_VILLAGER).draw(player.getLocation(), Shapes.circle(1, 16));
    }
}
```

Its test, `src/test/java/{{packagePath}}/ShopMenuTest.java`:

```java
package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class ShopMenuTest {
    private ServerMock server;
    private {{mainClass}} plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load({{mainClass}}.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void buyingTakesCoinsOnceUntilTheCooldownEnds() {
        PlayerMock alex = server.addPlayer("Alex");
        ShopMenu shop = new ShopMenu(plugin.wallet());

        shop.buy(alex);
        shop.buy(alex);

        assertThat(plugin.wallet().coins(alex)).isEqualTo(50);
        assertThat(alex.getInventory().contains(Material.DIAMOND)).isTrue();
    }
}
```

Its test, `src/test/java/{{packagePath}}/CountdownCommandTest.java`:

```java
package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class CountdownCommandTest {
    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        MockBukkit.load({{mainClass}}.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void countdownRepliesWithTheSeconds() {
        PlayerMock alex = server.addPlayer("Alex");
        alex.nextComponentMessage(); // the join greeting

        alex.performCommand("countdown 5");

        assertThat(PlainTextComponentSerializer.plainText().serialize(alex.nextComponentMessage()))
                .isEqualTo("Starting in 5s");
    }
}
```

## Tests

- Every change comes with a test. Pure logic: plain JUnit + AssertJ. Events, commands, players: MockBukkit.
- MockBukkit pattern: `MockBukkit.mock()` and `MockBukkit.load({{mainClass}}.class)` in `@BeforeEach`, `MockBukkit.unmock()` in `@AfterEach`; `server.addPlayer("Steve")` fires a join; `player.performCommand("hello")` runs a command; read replies with `player.nextComponentMessage()`.
- Compare messages as plain text (`PlainTextComponentSerializer`), not by component equality.

## Dev server

- Offline mode, bound to {{serverIp}}:{{serverPort}}, flat creative world, peaceful, no nether, no structures.
- `run/eula.txt` must say `eula=true` before the server starts. Accepting the Minecraft EULA (https://aka.ms/MinecraftEULA) is the owner's decision: if it says `false`, ask them, never change it yourself.
- RCON on port {{rconPort}}; the password is `rcon.password` in `run/server.properties`.
- If Minecraft Server Creator tools are available, use them to start the server, read condensed logs and inspect the world instead of reading raw `run/logs/latest.log`.

## Testing a game

- The dev server is creative and peaceful. For combat, damage or hunger, switch it with the MSC tool `server_setup mode=game` (survival, normal difficulty); the start report says which mode is on.
- Chunks with no player nearby are not loaded, so nothing in them moves: mobs spawned there freeze. Keep game areas loaded with `world.addPluginChunkTicket(chunkX, chunkZ, plugin)` or `forceload add x1 z1 x2 z2`. Spawner blocks and natural spawning also need a player close by, so spawn waves from code.
- `tick sprint 600` on the console runs 600 ticks (30 game seconds) at full speed, so timers and waves come sooner.
- Log what the game does (stage started, wave cleared, player won) so tools can wait for it (`server_wait until=...`) and check it (`world_entities`), and add admin commands that jump to any stage.
