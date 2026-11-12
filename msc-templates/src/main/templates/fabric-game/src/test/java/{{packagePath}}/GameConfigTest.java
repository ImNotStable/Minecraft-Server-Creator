package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GameConfigTest {
    @BeforeAll
    static void bootstrap() {
        // Items and other registries must be loaded first.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static GameConfig withStages(List<Stage> stages) {
        return new GameConfig(GameType.ADVENTURE, Difficulty.NORMAL, 6000, false, true, Kit.of(), stages);
    }

    @Test
    void theGameIsValid() {
        GameConfig game = GameConfig.game();

        assertThat(game.stages()).extracting(Stage::name).containsExactly("start", "arena");
        assertThat(game.kit().items()).first().isEqualTo(new Kit.Entry(Items.WOODEN_SWORD, 1));
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
    void rejectsBadSettings() {
        Spot p = new Spot(0, -60, 0);
        assertThatThrownBy(() -> withStages(List.of())).hasMessageContaining("at least one stage");
        assertThatThrownBy(() -> withStages(List.of(new Stage("a", p), new Stage("A", p))))
                .hasMessageContaining("duplicate stage name A");
        assertThatThrownBy(() -> new Stage("two words", p)).hasMessageContaining("one word");
        assertThatThrownBy(() -> new Kit.Entry(Items.BREAD, 0)).hasMessageContaining("at least 1");
    }
}
