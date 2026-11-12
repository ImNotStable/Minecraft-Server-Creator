package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

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
}
