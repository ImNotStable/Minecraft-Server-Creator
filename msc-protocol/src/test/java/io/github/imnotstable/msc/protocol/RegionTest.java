package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionTest {
    @Test
    void cornersAreSorted() {
        Region r = Region.of("world", 5, 70, -2, 1, 64, 3);
        assertThat(r.min()).isEqualTo(new BlockPos(1, 64, -2));
        assertThat(r.max()).isEqualTo(new BlockPos(5, 70, 3));
        assertThat(r.volume()).isEqualTo(5L * 7 * 6);
    }

    @Test
    void indexIsYThenZThenX() {
        Region r = Region.of("world", 0, 0, 0, 2, 1, 1); // 3 x 2 x 2
        assertThat(r.index(0, 0, 0)).isZero();
        assertThat(r.index(1, 0, 0)).isEqualTo(1);
        assertThat(r.index(0, 0, 1)).isEqualTo(3);
        assertThat(r.index(0, 1, 0)).isEqualTo(6);
        assertThat(r.index(2, 1, 1)).isEqualTo(11);
        assertThatThrownBy(() -> r.index(3, 0, 0)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void limitIsEnforced() {
        assertThatThrownBy(() -> Region.of("w", 0, 0, 0, 999, 255, 999).requireWithinLimit())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spansBeyondIntDoNotWrapUnderTheLimit() {
        Region wide = Region.of("w", -2_000_000_000, 0, 0, 2_000_000_000, 0, 0);
        assertThat(wide.volume()).isEqualTo(4_000_000_001L);
        assertThatThrownBy(wide::requireWithinLimit).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(wide::sizeX).isInstanceOf(ArithmeticException.class);
        Region full = Region.of("w", Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertThat(full.volume()).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(full::requireWithinLimit).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gridValidatesSizeAndPalette() {
        Region r = Region.of("w", 0, 0, 0, 1, 0, 0);
        BlockGrid grid = new BlockGrid(r, List.of("minecraft:stone"), new int[] {0, -1});
        assertThat(grid.stateAt(0, 0, 0)).isEqualTo("minecraft:stone");
        assertThat(grid.stateAt(1, 0, 0)).isNull();
        assertThatThrownBy(() -> new BlockGrid(r, List.of("minecraft:stone"), new int[] {0}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlockGrid(r, List.of("minecraft:stone"), new int[] {0, 1}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
