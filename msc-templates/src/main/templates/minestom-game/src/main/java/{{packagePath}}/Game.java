package {{packageName}};

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.Weather;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's state and rules: which stage each player is on, joining, advancing and jumping between stages.
 * Every stage change is logged ({@code Steve -> stage 2/3 arena (goal)}) so tests and tools can wait for it.
 * Put your game logic here or in classes it calls; keep the server class and {@link GameEvents} thin.
 *
 * <p>Runs on the tick thread only: event listeners and commands (players', the console's through {@link Console},
 * msc's {@code server_command}) all run there, so its state needs no locks and a step like "read the stage, then
 * advance" cannot interleave with another. Code on any other thread hops back first with
 * {@code MinecraftServer.getSchedulerManager().execute(...)}. {@link #confineTo} makes a call from elsewhere fail.
 */
public final class Game {
    private static final Logger LOG = LoggerFactory.getLogger("game");

    private final GameConfig config;
    private final Instance world;
    private final Progress progress;
    private final Runnable onProgressChange;
    /** Who is standing in their stage's goal. A plain set: only the tick thread touches it. */
    private final Set<UUID> inGoal = new HashSet<>();
    private Predicate<Thread> gameThread = t -> true;

    /**
     * @param onProgressChange called after every progress change; the server flushes {@link Progress} off the
     *                         tick thread there. Tests pass {@code () -> {}}.
     */
    public Game(GameConfig config, Instance world, Progress progress, Runnable onProgressChange) {
        this.config = config;
        this.world = world;
        this.progress = progress;
        this.onProgressChange = onProgressChange;
    }

    /**
     * From now on, a call that changes game state from a thread {@code isGameThread} rejects throws instead of
     * racing the tick. The server passes {@link #isTickThread}; tests pass the test thread too, since they act
     * for the tick there.
     */
    public Game confineTo(Predicate<Thread> isGameThread) {
        this.gameThread = isGameThread;
        return this;
    }

    /** True on Minestom's tick threads: the one that runs scheduler tasks and instance ticks, and those it waits for. */
    public static boolean isTickThread(Thread thread) {
        return thread.getName().startsWith(MinecraftServer.THREAD_NAME_TICK);
    }

    private void checkThread() {
        Thread current = Thread.currentThread();
        if (!gameThread.test(current)) {
            throw new IllegalStateException("game state changed on thread " + current.getName()
                    + "; hop to the tick thread first: MinecraftServer.getSchedulerManager().execute(...)");
        }
    }

    public GameConfig config() {
        return config;
    }

    public Instance world() {
        return world;
    }

    /** Frozen time and clear weather. Difficulty is server-wide, so the server class sets it. */
    public void applyWorldRules() {
        world.defaultClock().time(config.time());
        world.defaultClock().pause();
        world.setWeather(Weather.CLEAR);
    }

    /** Index of the player's current stage: the saved one, or 0 for a new player or a removed stage. */
    public int stageIndex(UUID player) {
        String saved = progress.stage(player);
        int i = saved == null ? -1 : config.stageIndex(saved);
        return Math.max(i, 0);
    }

    public Stage stage(Player player) {
        return config.stages().get(stageIndex(player.getUuid()));
    }

    public Pos spawn(Player player) {
        return stage(player).spawn();
    }

    /** On join: game mode, a fresh kit, and back to the saved stage. */
    public void join(Player player) {
        player.setGameMode(config.gameMode());
        config.kit().give(player);
        player.setRespawnPoint(spawn(player));
        player.teleport(spawn(player));
    }

    /** Puts the player on stage {@code index} (0-based), saves it and teleports them there. */
    public void moveTo(Player player, int index, String why) {
        checkThread();
        Stage stage = config.stages().get(index);
        progress.setStage(player.getUuid(), stage.name());
        onProgressChange.run();
        inGoal.remove(player.getUuid());
        player.setRespawnPoint(stage.spawn());
        player.teleport(stage.spawn());
        String where = "stage " + (index + 1) + "/" + config.stages().size() + " " + stage.name();
        player.sendMessage(Component.text("Stage " + (index + 1) + "/" + config.stages().size() + ": " + stage.name(),
                NamedTextColor.GOLD));
        LOG.info("{} -> {} ({})", player.getUsername(), where, why);
    }

    /** Completes the player's current stage: on to the next one, or finished after the last. */
    public void advance(Player player, String why) {
        checkThread();
        int next = stageIndex(player.getUuid()) + 1;
        if (next < config.stages().size()) {
            moveTo(player, next, why);
        } else {
            player.sendMessage(Component.text("You finished the game!", NamedTextColor.GREEN));
            LOG.info("{} finished the game ({})", player.getUsername(), why);
        }
    }

    /** Forgets the player's progress and starts them again from the first stage with a fresh kit. */
    public void reset(Player player) {
        checkThread();
        progress.clear(player.getUuid());
        config.kit().give(player);
        moveTo(player, 0, "reset");
    }

    /** Called when a player moves: completes the stage when they step into its goal. */
    public void moved(Player player, Point to) {
        checkThread();
        Box goal = stage(player).goal();
        boolean inside = goal != null && goal.contains(to);
        if (!inside) {
            inGoal.remove(player.getUuid());
        } else if (inGoal.add(player.getUuid())) {
            advance(player, "goal");
        }
    }

    public void quit(Player player) {
        checkThread();
        inGoal.remove(player.getUuid());
    }

    /** One line for the game, one per online player. What {@code /game status} prints. */
    public List<String> status(Collection<Player> online) {
        List<String> lines = new ArrayList<>();
        lines.add("game: " + config.stages().size() + " stages, time " + world.getTime() + ", "
                + config.difficulty().name().toLowerCase(Locale.ROOT) + ", " + online.size() + " online, "
                + progress.size() + " saved");
        for (Player p : online) {
            int i = stageIndex(p.getUuid());
            Pos at = p.getPosition();
            lines.add(p.getUsername() + ": stage " + (i + 1) + "/" + config.stages().size() + " "
                    + config.stages().get(i).name() + ", at " + at.blockX() + " " + at.blockY() + " " + at.blockZ()
                    + ", " + p.getGameMode().name().toLowerCase(Locale.ROOT) + ", health " + Math.round(p.getHealth()));
        }
        return lines;
    }
}
