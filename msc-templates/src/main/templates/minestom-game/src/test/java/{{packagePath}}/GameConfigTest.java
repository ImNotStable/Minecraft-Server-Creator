package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;

class GameConfigTest {
    /** Your game: building it runs every check, so a bad setting in {@link GameConfig#game()} fails here. */
    @Test
    void theGameIsValid() {
        assertThat(GameConfig.game().stages()).isNotEmpty();
    }

    @Test
    void findsStagesByNameOrNumber() {
        GameConfig game = TestGames.twoStages();

        assertThat(game.stageIndex("Arena")).isEqualTo(1);
        assertThat(game.stageIndex("1")).isZero();
        assertThat(game.stageIndex("3")).isEqualTo(-1);
        assertThat(game.stageIndex("lava")).isEqualTo(-1);
    }

    @Test
    void rejectsBadStages() {
        Pos p = new Pos(0, -60, 0);
        assertThatThrownBy(() -> TestGames.withStages(List.of())).hasMessageContaining("at least one stage");
        assertThatThrownBy(() -> TestGames.withStages(List.of(new Stage("a", p), new Stage("A", p))))
                .hasMessageContaining("duplicate stage name A");
        assertThatThrownBy(() -> new Stage("two words", p)).hasMessageContaining("one word");
    }

    @Test
    void adminNamesIgnoreCase() {
        GameConfig game = TestGames.withStages(List.of(new Stage("a", new Pos(0, 0, 0))));

        assertThat(game.isAdmin("steve")).isTrue();
        assertThat(game.isAdmin("Alex")).isFalse();
    }
}
