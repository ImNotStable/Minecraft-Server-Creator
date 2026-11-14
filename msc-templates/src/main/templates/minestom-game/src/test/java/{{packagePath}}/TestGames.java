package {{packageName}};

import java.util.List;
import java.util.Set;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.item.Material;
import net.minestom.server.world.Difficulty;

/**
 * The game the tests play. It is fixed here, not taken from {@link GameConfig#game()}, so editing your game's stages,
 * kit, rules or admins does not break the tests. When you add a field to {@link GameConfig}, give it a value here too.
 */
final class TestGames {
    private TestGames() {
    }

    /** Two stages: start, whose goal box leads on, and arena. */
    static GameConfig twoStages() {
        return withStages(List.of(
                new Stage("start", new Pos(0.5, -60, 0.5), Box.of(-2, -61, 10, 2, -58, 12)),
                new Stage("arena", new Pos(0.5, -60, 40.5))));
    }

    /** These stages, a wooden sword and 8 bread as the kit, and Steve as the one admin. */
    static GameConfig withStages(List<Stage> stages) {
        return new GameConfig(GameMode.ADVENTURE, Difficulty.NORMAL, 6000,
                Kit.of(Kit.item(Material.WOODEN_SWORD, 1), Kit.item(Material.BREAD, 8)), stages, Set.of("Steve"));
    }
}
