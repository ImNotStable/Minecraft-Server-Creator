package {{packageName}};

import java.util.List;
import java.util.Set;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.item.Material;
import net.minestom.server.world.Difficulty;

/**
 * The game the tests play. It is fixed here, not taken from {@link GameConfig#game()}, so editing {@code game()} does
 * not break the tests. When you add a field to {@link GameConfig}, give it a value here too.
 */
final class TestGames {
    /** The time of day and bread the tests play with, kept inside GameConfig.MAX_TIME and Kit.MAX_AMOUNT. */
    static final long TIME = Math.min(6000, GameConfig.MAX_TIME);
    static final int BREAD = Math.min(8, Kit.MAX_AMOUNT);

    private TestGames() {
    }

    /** Two stages: start, whose goal box leads on, and arena. */
    static GameConfig twoStages() {
        return withStages(List.of(
                new Stage("start", new Pos(0.5, -60, 0.5), Box.of(-2, -61, 10, 2, -58, 12)),
                new Stage("arena", new Pos(0.5, -60, 40.5))));
    }

    /** These stages, a wooden sword and {@link #BREAD} bread as the kit, and Steve as the one admin. */
    static GameConfig withStages(List<Stage> stages) {
        return with(TIME, stages);
    }

    /** As {@link #withStages}, at this time of day. */
    static GameConfig with(long time, List<Stage> stages) {
        return new GameConfig(GameMode.ADVENTURE, Difficulty.NORMAL, time,
                Kit.of(Kit.item(Material.WOODEN_SWORD, 1), Kit.item(Material.BREAD, BREAD)), stages, Set.of("Steve"));
    }
}
