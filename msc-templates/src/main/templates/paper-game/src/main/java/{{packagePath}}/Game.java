package {{packageName}};

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * The game's state and rules: which stage each player is on, joining, advancing and jumping between stages.
 * Every stage change is logged ({@code Steve -> stage 2/3 arena}) so tests and tools can wait for it.
 * Put your game logic here or in classes it calls; keep the plugin class and listeners thin.
 */
public final class Game {
    private final GameConfig config;
    private final World world;
    private final Progress progress;
    private final Logger log;
    private final Runnable onProgressChange;
    private final Set<UUID> inGoal = new HashSet<>();

    public Game(GameConfig config, World world, Progress progress, Logger log, Runnable onProgressChange) {
        this.config = config;
        this.world = world;
        this.progress = progress;
        this.log = log;
        this.onProgressChange = onProgressChange;
    }

    public GameConfig config() {
        return config;
    }

    /** Index of the player's current stage: the saved one, or 0 for a new player or a removed stage. */
    public int stageIndex(UUID player) {
        String saved = progress.stage(player);
        int i = saved == null ? -1 : config.stageIndex(saved);
        return Math.max(i, 0);
    }

    public Stage stage(Player player) {
        return config.stages().get(stageIndex(player.getUniqueId()));
    }

    public Location spawn(Player player) {
        return stage(player).spawn().in(world);
    }

    /** On join: game mode, a fresh kit, and back to the saved stage. */
    public void join(Player player) {
        player.setGameMode(config.gameMode());
        config.kit().give(player);
        player.teleport(spawn(player));
    }

    /** Puts the player on stage {@code index} (0-based), saves it and teleports them there. */
    public void moveTo(Player player, int index, String why) {
        Stage stage = config.stages().get(index);
        progress.setStage(player.getUniqueId(), stage.name());
        onProgressChange.run();
        inGoal.remove(player.getUniqueId());
        player.teleport(stage.spawn().in(world));
        player.sendMessage(Component.text("Stage " + (index + 1) + "/" + config.stages().size() + ": " + stage.name(),
                NamedTextColor.GOLD));
        log.info(player.getName() + " -> stage " + (index + 1) + "/" + config.stages().size() + " " + stage.name()
                + " (" + why + ")");
    }

    /** Completes the player's current stage: on to the next one, or finished after the last. */
    public void advance(Player player, String why) {
        int next = stageIndex(player.getUniqueId()) + 1;
        if (next < config.stages().size()) {
            moveTo(player, next, why);
        } else {
            player.sendMessage(Component.text("You finished the game!", NamedTextColor.GREEN));
            log.info(player.getName() + " finished the game (" + why + ")");
        }
    }

    /** Forgets the player's progress and starts them again from the first stage with a fresh kit. */
    public void reset(Player player) {
        progress.clear(player.getUniqueId());
        onProgressChange.run();
        config.kit().give(player);
        moveTo(player, 0, "reset");
    }

    /** Called when a player moves to another block: completes the stage when they step into its goal. */
    public void moved(Player player, int x, int y, int z) {
        Box goal = stage(player).goal();
        boolean inside = goal != null && goal.contains(x, y, z);
        if (!inside) {
            inGoal.remove(player.getUniqueId());
        } else if (inGoal.add(player.getUniqueId())) {
            advance(player, "goal");
        }
    }

    public void quit(Player player) {
        inGoal.remove(player.getUniqueId());
    }

    /** One line for the game, one per online player. What {@code /game status} prints. */
    public List<String> status(List<? extends Player> online) {
        List<String> lines = new ArrayList<>();
        lines.add("game: " + config.stages().size() + " stages, world " + world.getName() + ", time " + world.getTime()
                + ", " + world.getDifficulty().name().toLowerCase(java.util.Locale.ROOT) + ", " + online.size() + " online, "
                + progress.size() + " saved");
        for (Player p : online) {
            int i = stageIndex(p.getUniqueId());
            Location at = p.getLocation();
            lines.add(p.getName() + ": stage " + (i + 1) + "/" + config.stages().size() + " "
                    + config.stages().get(i).name() + ", at " + at.getBlockX() + " " + at.getBlockY() + " "
                    + at.getBlockZ() + ", " + p.getGameMode().name().toLowerCase(java.util.Locale.ROOT) + ", health "
                    + Math.round(p.getHealth()));
        }
        return lines;
    }
}
