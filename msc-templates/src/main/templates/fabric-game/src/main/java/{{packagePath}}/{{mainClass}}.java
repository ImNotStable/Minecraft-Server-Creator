package {{packageName}};

import io.github.imnotstable.msc.stdlib.fabric.Stdlib;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entry point: builds the game from {@link GameConfig#game()}, loads saved progress, and wires the events and
 * {@code /game}. Holds no game logic itself.
 */
public final class {{mainClass}} implements DedicatedServerModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("{{projectId}}");

    @Override
    public void onInitializeServer() {
        Stdlib.init(); // msc-stdlib: its scheduler runs on the server tick
        GameConfig config = GameConfig.game();
        Progress progress;
        try {
            // In the server directory (run/ in development), next to the world.
            progress = new Progress(FabricLoader.getInstance().getGameDir().resolve("{{projectId}}-progress.properties")).load();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read {{projectId}}-progress.properties", e);
        }
        Game game = new Game(config, progress, () -> Thread.startVirtualThread(() -> save(progress)));
        GameEvents.register(game, () -> save(progress));
        GameCommand command = new GameCommand(game);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> command.register(dispatcher));
        LOG.info("game ready: {} stages. Try: game status", config.stages().size());
    }

    private static void save(Progress progress) {
        try {
            progress.flush();
        } catch (IOException e) {
            LOG.error("could not save progress", e);
        }
    }
}
