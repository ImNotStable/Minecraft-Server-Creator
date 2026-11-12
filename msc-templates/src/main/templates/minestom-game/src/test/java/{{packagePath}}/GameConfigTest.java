package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.world.Difficulty;
import org.junit.jupiter.api.Test;

class GameConfigTest {
    private static GameConfig withStages(List<Stage> stages) {
        return new GameConfig(GameMode.ADVENTURE, Difficulty.NORMAL, 6000, Kit.of(), stages, Set.of("Steve"));
    }

    @Test
    void theGameIsValid() {
        GameConfig game = GameConfig.game();

        assertThat(game.stages()).extracting(Stage::name).containsExactly("start", "arena");
        assertThat(game.kit().items()).hasSize(2);
    }

    @Test
    void findsStagesByNameOrNumber() {
        GameConfig game = GameConfig.game();

        assertThat(game.stageIndex("Arena")).isEqualTo(1);
        assertThat(game.stageIndex("1")).isZero();
        assertThat(game.stageIndex("3")).isEqualTo(-1);
        assertThat(game.stageIndex("lava")).isEqualTo(-1);
    }

    @Test
    void rejectsBadStages() {
        Pos p = new Pos(0, -60, 0);
        assertThatThrownBy(() -> withStages(List.of())).hasMessageContaining("at least one stage");
        assertThatThrownBy(() -> withStages(List.of(new Stage("a", p), new Stage("A", p))))
                .hasMessageContaining("duplicate stage name A");
        assertThatThrownBy(() -> new Stage("two words", p)).hasMessageContaining("one word");
    }

    @Test
    void adminNamesIgnoreCase() {
        GameConfig game = withStages(List.of(new Stage("a", new Pos(0, 0, 0))));

        assertThat(game.isAdmin("steve")).isTrue();
        assertThat(game.isAdmin("Alex")).isFalse();
    }
}
