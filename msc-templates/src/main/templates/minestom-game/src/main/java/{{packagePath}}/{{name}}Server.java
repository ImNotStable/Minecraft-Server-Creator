package {{packageName}};

import io.github.imnotstable.msc.agent.minestom.MscAgent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import net.minestom.server.MinecraftServer;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.InstanceManager;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.anvil.AnvilLoader;
import net.minestom.server.instance.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Only wiring here (world, game, events, commands); the game itself is {@link Game}, set up in
 * {@link GameConfig#game()}. Minestom has no vanilla gameplay: everything the server does is code in this project.
 */
public final class {{name}}Server {
    private static final Logger LOG = LoggerFactory.getLogger("{{projectId}}");

    private {{name}}Server() {
    }

    public static void main(String[] args) throws IOException {
        long start = System.nanoTime();
        MinecraftServer server = MinecraftServer.init();

        InstanceContainer world = createWorld(MinecraftServer.getInstanceManager());
        // Keep the world in run/world, so what the AI builds survives dev_cycle restarts. Saved on stop.
        world.setChunkLoader(new AnvilLoader(Path.of("world"), Key.key("minecraft:overworld")));

        GameConfig config = GameConfig.game();
        Progress progress = new Progress(Path.of("progress.properties")).load();
        Game game = new Game(config, world, progress, () -> Thread.startVirtualThread(() -> save(progress)));
        game.applyWorldRules();
        MinecraftServer.setDifficulty(config.difficulty());
        GameEvents.register(MinecraftServer.getGlobalEventHandler(), game);
        MinecraftServer.getCommandManager().register(
                new GameCommand(game, MinecraftServer.getConnectionManager()::findOnlinePlayer));
        MinecraftServer.getSchedulerManager().buildShutdownTask(() -> {
            save(progress);
            world.saveChunksToStorage().join();
        });

        // Minecraft Server Creator: lets the AI's tools see and edit "world". Idle when not started by msc-core.
        MscAgent.name(world, "world");
        MscAgent.start();
        Console.start();

        server.start("{{serverIp}}", {{serverPort}});
        LOG.info("game ready: {} stages. Try: game status", config.stages().size());
        System.out.printf(Locale.ROOT, "Done (%.3fs)! Listening on {{serverIp}}:{{serverPort}}. Type stop to stop.%n",
                (System.nanoTime() - start) / 1e9);
    }

    /** A flat world like vanilla's default superflat: bedrock, two dirt, grass, from y=-64. Players stand at y -60. */
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

    private static void save(Progress progress) {
        try {
            progress.flush();
        } catch (IOException e) {
            LOG.error("could not save progress.properties", new UncheckedIOException(e));
        }
    }
}
