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
- Prefer small final classes and records, except the main plugin class: MockBukkit must subclass it, so never make it final. Pass dependencies through constructors; avoid static state so MockBukkit tests stay isolated.

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

The entry point with both wired in, the listener only while the setting `protect-blocks` is on (add `protect-blocks: false` to `config.yml`):

```java
package {{packageName}};

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point: wires config, listeners and commands, and holds no game logic itself.
 * Not final: MockBukkit subclasses the main class when loading it in tests.
 */
public class {{mainClass}} extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        Greeter greeter = new Greeter(
                getConfig().getString("join-message", Greeter.DEFAULT_JOIN),
                getConfig().getString("hello-message", Greeter.DEFAULT_HELLO));

        getServer().getPluginManager().registerEvents(new JoinListener(greeter), this);
        if (getConfig().getBoolean("protect-blocks", false)) {
            getServer().getPluginManager().registerEvents(new BlockGuard(), this);
        }
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("hello", "Says hello to you.", new HelloCommand(greeter));
            event.registrar().register(CountdownCommand.node(), "Counts down.");
        });
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
