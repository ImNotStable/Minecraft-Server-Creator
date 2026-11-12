package {{packageName}};

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * The game's settings, checked once when built: a bad value fails with a message naming it.
 * Edit {@link #game()} to set up your game. Coordinates fit the dev server's flat world, where players stand at y -60.
 *
 * @param gameMode      game mode for players: ADVENTURE (no breaking or placing), SURVIVAL or CREATIVE
 * @param difficulty    world difficulty (the dev server starts peaceful; this overrides it)
 * @param time          time of day, frozen (0 sunrise, 6000 noon, 13000 night, 18000 midnight); weather stays clear
 * @param naturalSpawns whether the world spawns mobs on its own; mobs your code, commands and spawners make still appear
 * @param keepInventory whether players keep their items and levels when they die
 * @param kit           items every player gets on join (inventory cleared first) and on {@code /game kit}
 * @param stages        stages in play order: players start at the first, respawn at their current one, and advance
 *                      when they step into its goal box
 */
public record GameConfig(GameType gameMode, Difficulty difficulty, long time, boolean naturalSpawns,
                         boolean keepInventory, Kit kit, List<Stage> stages) {
    public GameConfig {
        Objects.requireNonNull(gameMode, "gameMode");
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(kit, "kit");
        stages = List.copyOf(stages);
        if (stages.isEmpty()) {
            throw new IllegalArgumentException("stages: add at least one stage");
        }
        Set<String> names = new HashSet<>();
        for (Stage s : stages) {
            if (!names.add(s.name().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("stages: duplicate stage name " + s.name());
            }
        }
    }

    /** This game. Change it here: rules, kit, and stages with spawn points and goal boxes. */
    public static GameConfig game() {
        return new GameConfig(
                GameType.ADVENTURE,
                Difficulty.NORMAL,
                6000,
                false,
                true,
                Kit.of(new Kit.Entry(Items.WOODEN_SWORD, 1), new Kit.Entry(Items.BREAD, 8)),
                List.of(
                        new Stage("start", new Spot(0.5, -60, 0.5), Box.of(-2, -61, 10, 2, -58, 12)),
                        new Stage("arena", new Spot(0.5, -60, 40.5))));
    }

    /** Index of the stage with this name or 1-based number, or -1. */
    public int stageIndex(String nameOrNumber) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).name().equalsIgnoreCase(nameOrNumber)) {
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
}
