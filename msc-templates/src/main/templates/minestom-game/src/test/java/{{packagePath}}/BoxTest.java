package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;

/** Plain unit test: no server needed. Prefer this kind of test for logic. */
class BoxTest {
    @Test
    void cornersMayComeInAnyOrder() {
        Box box = Box.of(2, -58, 12, -2, -61, 10);

        assertThat(box).isEqualTo(new Box(-2, -61, 10, 2, -58, 12));
        assertThat(box.contains(0, -60, 11)).isTrue();
        assertThat(box.contains(3, -60, 11)).isFalse();
    }

    @Test
    void containsUsesTheBlockAPointIsIn() {
        Box box = Box.of(0, 0, 0, 0, 0, 0);

        assertThat(box.contains(new Pos(0.9, 0.5, 0.1))).isTrue();
        assertThat(box.contains(new Pos(-0.1, 0, 0))).isFalse();
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
            assertThatThrownBy(() -> stageAt(bad, 0, 0, 0, 0)).hasMessageContaining("not finite or outside");
            assertThatThrownBy(() -> stageAt(0, bad, 0, 0, 0)).hasMessageContaining("not finite or outside");
            assertThatThrownBy(() -> stageAt(0, 0, bad, 0, 0)).hasMessageContaining("not finite or outside");
            if (!Double.isFinite(bad)) { // Pos itself wraps a finite yaw into -180 to 180
                assertThatThrownBy(() -> stageAt(0, 0, 0, (float) bad, 0)).hasMessageContaining("not finite or outside");
            }
        }
        // Pos clamps pitch into -90 to 90 itself; only NaN gets through to the check.
        assertThatThrownBy(() -> stageAt(0, 0, 0, 0, Float.NaN)).hasMessageContaining("not finite or outside");
        assertThat(stageAt(0.5, -60, 0.5, 180, -90)).isNotNull();
    }

    private static Stage stageAt(double x, double y, double z, float yaw, float pitch) {
        return new Stage("a", new Pos(x, y, z, yaw, pitch));
    }
}
