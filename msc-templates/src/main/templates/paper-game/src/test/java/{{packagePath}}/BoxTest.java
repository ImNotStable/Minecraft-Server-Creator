package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Plain unit tests: no server needed. Prefer this kind of test for game logic. */
class BoxTest {
    @Test
    void cornersInAnyOrderAndInclusive() {
        Box box = Box.parse(List.of(2, -58, 12, -2, -61, 10), "goal");
        assertThat(box.contains(-2, -61, 10)).isTrue();
        assertThat(box.contains(2, -58, 12)).isTrue();
        assertThat(box.contains(0, -60, 11)).isTrue();
        assertThat(box.contains(3, -60, 11)).isFalse();
    }

    @Test
    void badBoxesNameTheKey() {
        assertThatThrownBy(() -> Box.parse(List.of(1, 2, 3), "stages[0].goal")).hasMessageContaining("stages[0].goal");
        assertThatThrownBy(() -> Spot.parse(List.of(1, "x", 3), "stages[1].spawn")).hasMessageContaining("stages[1].spawn");
    }

    @Test
    void spotsTakeAnOptionalViewDirection() {
        assertThat(Spot.parse(List.of(0.5, -60, 0.5), "s")).isEqualTo(new Spot(0.5, -60, 0.5, 0, 0));
        assertThat(Spot.parse(List.of(1, 2, 3, 90, 10), "s")).isEqualTo(new Spot(1, 2, 3, 90, 10));
    }
}
