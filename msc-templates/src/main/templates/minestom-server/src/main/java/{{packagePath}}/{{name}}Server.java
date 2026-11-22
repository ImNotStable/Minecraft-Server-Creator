package {{packageName}};

import io.github.imnotstable.msc.agent.minestom.MscAgent;
import io.github.imnotstable.msc.stdlib.minestom.Stdlib;
import java.nio.file.Path;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandManager;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.anvil.AnvilLoader;
import net.minestom.server.instance.block.Block;

/**
 * Entry point. Only wiring here (world, events, commands); put logic in its own classes.
 * Minestom has no vanilla gameplay: everything the server does is code in this project.
 */
public final class {{name}}Server {
    /** Where players appear: on top of the flat world's grass. */
    static final Pos SPAWN = new Pos(0.5, -60, 0.5);

    private {{name}}Server() {
    }

    public static void main(String[] args) {
        long start = System.nanoTime();
        MinecraftServer server = MinecraftServer.init();
        Stdlib.init(MinecraftServer.process()); // msc-stdlib: menus, player data and its scheduler need it

        InstanceContainer world = createWorld(MinecraftServer.getInstanceManager());
        // Keep the world in run/world, so what the AI builds survives dev_cycle restarts. Saved on stop.
        world.setChunkLoader(new AnvilLoader(Path.of("world"), Key.key("minecraft:overworld")));
        MinecraftServer.getSchedulerManager().buildShutdownTask(() -> world.saveChunksToStorage().join());
        registerEvents(MinecraftServer.getGlobalEventHandler(), world);
        registerCommands(MinecraftServer.getCommandManager(), new Greeter());

        // Minecraft Server Creator: lets the AI's tools see and edit "world". Idle when not started by msc-core.
        MscAgent.name(world, "world");
        MscAgent.start();
        Console.start();

        server.start("{{serverIp}}", {{serverPort}});
        System.out.printf(Locale.ROOT, "Done (%.3fs)! Listening on {{serverIp}}:{{serverPort}}. Type stop to stop.%n",
                (System.nanoTime() - start) / 1e9);
    }

    /** A flat world like vanilla's default superflat: bedrock, two dirt, grass, from y=-64. */
    static InstanceContainer createWorld(InstanceManager instances) {
        InstanceContainer world = instances.createInstanceContainer();
        world.setChunkSupplier(LightingChunk::new);
        world.setGenerator(unit -> {
            unit.modifier().fillHeight(-64, -63, Block.BEDROCK);
            unit.modifier().fillHeight(-63, -61, Block.DIRT);
            unit.modifier().fillHeight(-61, -60, Block.GRASS_BLOCK);
        });
        return world;
    }

    static void registerEvents(GlobalEventHandler events, Instance world) {
        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(world);
            event.getPlayer().setRespawnPoint(SPAWN);
        });
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) {
                event.getPlayer().setGameMode(GameMode.CREATIVE);
            }
        });
    }

    static void registerCommands(CommandManager commands, Greeter greeter) {
        commands.register(new HelloCommand(greeter));
    }
}
