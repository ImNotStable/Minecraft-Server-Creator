package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** Plain unit test: no server needed. Prefer this kind of test for logic. */
class BoxTest {
    @Test
    void cornersMayComeInAnyOrder() {
        Box box = Box.of(2, -58, 12, -2, -61, 10);

        assertThat(box).isEqualTo(new Box(-2, -61, 10, 2, -58, 12));
        assertThat(box.contains(new BlockPos(0, -60, 11))).isTrue();
        assertThat(box.contains(new BlockPos(3, -60, 11))).isFalse();
    }

    @Test
    void boxesMustBeInOrderAndInsideTheWorld() {
        assertThatThrownBy(() -> new Box(2, 0, 0, -2, 0, 0)).hasMessageContaining("use Box.of");
        assertThatThrownBy(() -> new Box(0, 5, 0, 0, 4, 0)).hasMessageContaining("use Box.of");
        assertThatThrownBy(() -> Box.of(0, 0, 0, 0, 2032, 0)).hasMessageContaining("outside the world");
        assertThatThrownBy(() -> Box.of(0, -2033, 0, 0, 0, 0)).hasMessageContaining("outside the world");
        assertThatThrownBy(() -> Box.of(-30_000_001, 0, 0, 0, 0, 0)).hasMessageContaining("outside the world");
        assertThatThrownBy(() -> Box.of(0, 0, 0, 0, 0, Integer.MAX_VALUE)).hasMessageContaining("outside the world");
        assertThat(Box.of(-30_000_000, -2032, -30_000_000, 30_000_000, 2031, 30_000_000)).isNotNull();
    }

    @Test
    void spawnsMustBeFiniteAndInsideTheWorld() {
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 3e7 + 1}) {
            assertThatThrownBy(() -> new Spot(bad, 0, 0, 0, 0)).hasMessageContaining("not finite or outside");
            assertThatThrownBy(() -> new Spot(0, bad, 0, 0, 0)).hasMessageContaining("not finite or outside");
            assertThatThrownBy(() -> new Spot(0, 0, bad, 0, 0)).hasMessageContaining("not finite or outside");
            assertThatThrownBy(() -> new Spot(0, 0, 0, (float) bad, 0)).hasMessageContaining("not finite or outside");
            assertThatThrownBy(() -> new Spot(0, 0, 0, 0, (float) bad)).hasMessageContaining("not finite or outside");
        }
        assertThatThrownBy(() -> new Spot(0, 0, 0, 0, 91)).hasMessageContaining("not finite or outside");
        assertThat(new Spot(0.5, -60, 0.5, 180, -90)).isNotNull();
    }
}
