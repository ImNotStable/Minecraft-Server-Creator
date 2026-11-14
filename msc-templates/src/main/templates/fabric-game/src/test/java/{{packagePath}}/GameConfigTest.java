package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GameConfigTest {
    @BeforeAll
    static void bootstrap() {
        // Items and other registries must be loaded first.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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
    void rejectsBadSettings() {
        Spot p = new Spot(0, -60, 0);
        assertThatThrownBy(() -> TestGames.withStages(List.of())).hasMessageContaining("at least one stage");
        assertThatThrownBy(() -> TestGames.withStages(List.of(new Stage("a", p), new Stage("A", p))))
                .hasMessageContaining("duplicate stage name A");
        assertThatThrownBy(() -> new Stage("two words", p)).hasMessageContaining(Stage.NAME_RULE);
        assertThatThrownBy(() -> new Kit.Entry(Items.BREAD, 0)).hasMessageContaining("from 1 to " + Kit.MAX_AMOUNT);
    }
}
