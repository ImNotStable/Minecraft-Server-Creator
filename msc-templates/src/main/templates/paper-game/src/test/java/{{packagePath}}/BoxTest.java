package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Plain unit tests: no server needed. Prefer this kind of test for game logic. */
class BoxTest {
    @Test
    void cornersInAnyOrderAndInclusive() {
        Box box = Box.of(2, -58, 12, -2, -61, 10);
        assertThat(box).isEqualTo(new Box(-2, -61, 10, 2, -58, 12));
        assertThat(box.contains(-2, -61, 10)).isTrue();
        assertThat(box.contains(2, -58, 12)).isTrue();
        assertThat(box.contains(0, -60, 11)).isTrue();
        assertThat(box.contains(3, -60, 11)).isFalse();
    }

    @Test
    void boxesBuiltInCodeMustBeInOrderAndInsideTheWorld() {
        assertThatThrownBy(() -> new Box(2, 0, 0, -2, 0, 0)).hasMessageContaining("use Box.of");
        assertThatThrownBy(() -> Box.of(0, 0, 0, 0, Box.MAX_Y + 1, 0)).hasMessageContaining("outside the world");
        assertThatThrownBy(() -> Box.of(0, Box.MIN_Y - 1, 0, 0, 0, 0)).hasMessageContaining("outside the world");
        assertThatThrownBy(() -> Box.of(-Box.MAX_XZ - 1, 0, 0, 0, 0, 0)).hasMessageContaining("outside the world");
        assertThatThrownBy(() -> Box.of(0, 0, 0, 0, 0, Box.MAX_XZ + 1)).hasMessageContaining("outside the world");
        assertThat(Box.of(-Box.MAX_XZ, Box.MIN_Y, -Box.MAX_XZ, Box.MAX_XZ, Box.MAX_Y, Box.MAX_XZ)).isNotNull();
    }

    @Test
    void spotsMustBeFiniteAndInsideTheWorld() {
        assertThat(new Spot(0.5, -60, 0.5, 90, 10)).hasToString("0.5 -60.0 0.5");
        for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThatThrownBy(() -> new Spot(bad, 0, 0, 0, 0)).hasMessageContaining("not finite or outside");
            assertThatThrownBy(() -> new Spot(0, bad, 0, 0, 0)).hasMessageContaining("not finite or outside");
            assertThatThrownBy(() -> new Spot(0, 0, 0, (float) bad, 0)).hasMessageContaining("not finite or outside");
        }
        assertThatThrownBy(() -> new Spot(Box.MAX_XZ + 0.5, 0, 0, 0, 0)).hasMessageContaining("not finite or outside");
        assertThatThrownBy(() -> new Spot(0, Box.MAX_Y + 0.5, 0, 0, 0)).hasMessageContaining("not finite or outside");
        assertThatThrownBy(() -> new Spot(0, Box.MIN_Y - 0.5, 0, 0, 0)).hasMessageContaining("not finite or outside");
        assertThatThrownBy(() -> new Spot(0, 0, -Box.MAX_XZ - 0.5, 0, 0)).hasMessageContaining("not finite or outside");
        assertThatThrownBy(() -> new Spot(0, 0, 0, 361, 0)).hasMessageContaining("not finite or outside");
        assertThatThrownBy(() -> new Spot(0, 0, 0, 0, 91)).hasMessageContaining("not finite or outside");
    }
}
