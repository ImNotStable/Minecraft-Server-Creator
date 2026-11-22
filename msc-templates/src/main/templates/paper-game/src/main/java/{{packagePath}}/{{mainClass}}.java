package {{packageName}};

import io.github.imnotstable.msc.stdlib.paper.Stdlib;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Plugin entry point: reads config, applies the world rules and wires the game, its listener and /game.
 * Holds no game logic itself. Not final: MockBukkit subclasses the main class when loading it in tests.
 */
public class {{mainClass}} extends JavaPlugin {
    static final String ADMIN_PERMISSION = "{{projectId}}.admin";

    private @Nullable Game game;
    private @Nullable Progress progress;

    @Override
    public void onEnable() {
        Stdlib.init(this); // msc-stdlib: GUIs, player data and its scheduler need it
        saveDefaultConfig();
        GameConfig config;
        try {
            config = GameConfig.from(getConfig());
        } catch (IllegalArgumentException e) {
            fail("config.yml: " + e.getMessage(), null);
            return;
        }
        World world = getServer().getWorld(config.world());
        if (world == null) {
            fail("config.yml: world " + config.world() + " is not loaded", null);
            return;
        }
        try {
            progress = new Progress(getDataFolder().toPath().resolve("progress.yml")).load();
        } catch (IOException e) {
            fail("could not read progress.yml: " + e.getMessage(), e);
            return;
        }
        WorldRules.apply(world, config);
        Progress saved = progress;
        game = new Game(config, world, saved, getLogger(),
                () -> getServer().getScheduler().runTaskAsynchronously(this, () -> save(saved)));

        getServer().getPluginManager().registerEvents(new GameListener(game), this);
        Game g = game;
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register(
                "game", "Game admin: status, stage, next, reset, kit.", new GameCommand(g, getServer(), ADMIN_PERMISSION)));
        getLogger().info("game ready: " + config.stages().size() + " stages in " + world.getName()
                + ". Try: game status");
    }

    @Override
    public void onDisable() {
        if (progress != null) {
            save(progress);
        }
    }

    /** The running game, for tests and for other classes of this plugin. Null if the plugin failed to start. */
    public @Nullable Game game() {
        return game;
    }

    private void save(Progress p) {
        try {
            p.flush();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "could not save progress.yml", e);
        }
    }

    /** Logs one clear error and disables the plugin, so the startup report names the problem. */
    private void fail(String message, @Nullable Exception cause) {
        getLogger().log(Level.SEVERE, message, cause);
        getServer().getPluginManager().disablePlugin(this);
    }
}
