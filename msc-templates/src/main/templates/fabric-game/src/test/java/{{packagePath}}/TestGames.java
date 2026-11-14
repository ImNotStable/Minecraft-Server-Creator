package {{packageName}};

import java.util.List;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;

/**
 * The game the tests play. It is fixed here, not taken from {@link GameConfig#game()}, so editing {@code game()} does
 * not break the tests. When you add a field to {@link GameConfig}, give it a value here too.
 */
final class TestGames {
    /** The time of day the tests play at, kept inside {@link GameConfig#MAX_TIME} when you lower it. */
    static final long TIME = Math.min(6000, GameConfig.MAX_TIME);

    private TestGames() {
    }

    /** Two stages: start, whose goal box leads on, and arena. */
    static GameConfig twoStages() {
        return withStages(List.of(
                new Stage("start", new Spot(0.5, -60, 0.5), Box.of(-2, -61, 10, 2, -58, 12)),
                new Stage("arena", new Spot(0.5, -60, 40.5))));
    }

    static GameConfig withStages(List<Stage> stages) {
        return with(TIME, stages);
    }

    /** These stages, at this time of day. */
    static GameConfig with(long time, List<Stage> stages) {
        return new GameConfig(GameType.ADVENTURE, Difficulty.NORMAL, time, false, true, Kit.of(), stages);
    }
}
