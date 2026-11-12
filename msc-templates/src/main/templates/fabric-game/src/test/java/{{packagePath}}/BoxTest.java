package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

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
}
