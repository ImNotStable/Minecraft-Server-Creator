package {{packageName}};

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's state and rules: which stage each player is on, joining, advancing and jumping between stages.
 * Every stage change is logged ({@code Steve -> stage 2/3 arena (goal)}) so tests and tools can wait for it.
 * Put your game logic here or in classes it calls; keep the mod class and {@link GameEvents} thin.
 * Runs on the server thread only.
 */
public final class Game {
    private static final Logger LOG = LoggerFactory.getLogger("game");

    private final GameConfig config;
    private final Progress progress;
    private final Runnable onProgressChange;
    private final Set<UUID> inGoal = new HashSet<>();
    private final Map<UUID, BlockPos> lastBlock = new HashMap<>();

    /**
     * @param onProgressChange called after every progress change; the mod flushes {@link Progress} off the
     *                         server thread there. Tests pass {@code () -> {}}.
     */
    public Game(GameConfig config, Progress progress, Runnable onProgressChange) {
        this.config = config;
        this.progress = progress;
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

    public Stage stage(ServerPlayer player) {
        return config.stages().get(stageIndex(player.getUUID()));
    }

    /** Teleports the player to the spawn of their current stage, in the overworld. */
    public void sendToSpawn(ServerPlayer player) {
        Spot s = stage(player).spawn();
        MinecraftServer server = player.level().getServer();
        player.teleportTo(server.overworld(), s.x(), s.y(), s.z(), Set.of(), s.yaw(), s.pitch(), true);
    }

    /** On join: game mode, a fresh kit, and back to the saved stage. */
    public void join(ServerPlayer player) {
        player.setGameMode(config.gameMode());
        config.kit().give(player);
        sendToSpawn(player);
    }

    /** Puts the player on stage {@code index} (0-based), saves it and teleports them there. */
    public void moveTo(ServerPlayer player, int index, String why) {
        Stage stage = config.stages().get(index);
        progress.setStage(player.getUUID(), stage.name());
        onProgressChange.run();
        inGoal.remove(player.getUUID());
        sendToSpawn(player);
        player.sendSystemMessage(Component.literal("Stage " + (index + 1) + "/" + config.stages().size() + ": "
                + stage.name()).withStyle(ChatFormatting.GOLD));
        LOG.info("{} -> stage {}/{} {} ({})", player.getPlainTextName(), index + 1, config.stages().size(),
                stage.name(), why);
    }

    /** Completes the player's current stage: on to the next one, or finished after the last. */
    public void advance(ServerPlayer player, String why) {
        int next = stageIndex(player.getUUID()) + 1;
        if (next < config.stages().size()) {
            moveTo(player, next, why);
        } else {
            player.sendSystemMessage(Component.literal("You finished the game!").withStyle(ChatFormatting.GREEN));
            LOG.info("{} finished the game ({})", player.getPlainTextName(), why);
        }
    }

    /** Forgets the player's progress and starts them again from the first stage with a fresh kit. */
    public void reset(ServerPlayer player) {
        progress.clear(player.getUUID());
        onProgressChange.run();
        config.kit().give(player);
        moveTo(player, 0, "reset");
    }

    /** Called every tick for each player: completes the stage when they step into its goal. */
    public void tick(ServerPlayer player) {
        BlockPos at = player.blockPosition();
        if (at.equals(lastBlock.put(player.getUUID(), at))) {
            return;
        }
        Box goal = stage(player).goal();
        if (goal == null || !goal.contains(at)) {
            inGoal.remove(player.getUUID());
        } else if (inGoal.add(player.getUUID())) {
            advance(player, "goal");
        }
    }

    public void quit(ServerPlayer player) {
        inGoal.remove(player.getUUID());
        lastBlock.remove(player.getUUID());
    }

    /** One line for the game, one per online player. What {@code /game status} prints. */
    public List<String> status(List<ServerPlayer> online) {
        List<String> lines = new ArrayList<>();
        lines.add("game: " + config.stages().size() + " stages, time " + config.time() + ", "
                + config.difficulty().getSerializedName() + ", " + online.size() + " online, " + progress.size()
                + " saved");
        for (ServerPlayer p : online) {
            int i = stageIndex(p.getUUID());
            BlockPos at = p.blockPosition();
            lines.add(p.getPlainTextName() + ": stage " + (i + 1) + "/" + config.stages().size() + " "
                    + config.stages().get(i).name() + ", at " + at.getX() + " " + at.getY() + " " + at.getZ() + ", "
                    + p.gameMode().getName().toLowerCase(Locale.ROOT) + ", health " + Math.round(p.getHealth()));
        }
        return lines;
    }
}
