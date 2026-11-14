package {{packageName}};

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.item.Material;
import net.minestom.server.world.Difficulty;

/**
 * The game's settings, checked once when built: a bad value fails with a message naming it.
 * Edit {@link #game()} to set up your game. Coordinates fit the flat world, where players stand at y -60.
 *
 * @param gameMode   game mode for players: adventure (no breaking or placing), survival or creative
 * @param difficulty server difficulty (Minestom only uses it for what your code reads)
 * @param time       time of day, frozen (0 sunrise, 6000 noon, 13000 night, 18000 midnight); weather stays clear
 * @param kit        items every player gets on join (inventory cleared first) and on {@code /game kit}
 * @param stages     stages in play order: players start at the first, respawn at their current one, and advance
 *                   when they step into its goal box
 * @param admins     player names allowed to use {@code /game} in game (the console always can)
 */
public record GameConfig(GameMode gameMode, Difficulty difficulty, long time, Kit kit, List<Stage> stages,
                         Set<String> admins) {
    /** The last tick of a day: {@code time} is 0 to this. */
    public static final long MAX_TIME = 23999;

    public GameConfig {
        Objects.requireNonNull(gameMode, "gameMode");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(kit, "kit");
        stages = List.copyOf(stages);
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("stages: add at least one stage");
        }
        if (time < 0 || time > MAX_TIME) {
            throw new IllegalArgumentException("time: needs a whole number from 0 to " + MAX_TIME + ", got " + time);
        }
        for (int i = 0; i < stages.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (Stage.sameName(stages.get(j).name(), stages.get(i).name())) {
                    throw new IllegalArgumentException("stages[" + i + "]: duplicate stage name " + stages.get(i).name());
                }
            }
        }
        admins = Set.copyOf(admins.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList());
    }

    /** This game. Change it here: kit, stages with spawn points and goal boxes, who may use /game. */
    public static GameConfig game() {
        return new GameConfig(
                GameMode.ADVENTURE,
                Difficulty.NORMAL,
                6000,
                Kit.of(Kit.item(Material.WOODEN_SWORD, 1), Kit.item(Material.BREAD, 8)),
                List.of(
                        new Stage("start", new Pos(0.5, -60, 0.5), Box.of(-2, -61, 10, 2, -58, 12)),
                        new Stage("arena", new Pos(0.5, -60, 40.5))),
                Set.of());
    }

    /** Index of the stage with this name or 1-based number, or -1. */
    public int stageIndex(String nameOrNumber) {
        for (int i = 0; i < stages.size(); i++) {
            if (Stage.sameName(stages.get(i).name(), nameOrNumber)) {
                return i;
            }
        }
        try {
            int n = Integer.parseInt(nameOrNumber);
            return n >= 1 && n <= stages.size() ? n - 1 : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public boolean isAdmin(String playerName) {
        return admins.contains(playerName.toLowerCase(Locale.ROOT));
    }
}
