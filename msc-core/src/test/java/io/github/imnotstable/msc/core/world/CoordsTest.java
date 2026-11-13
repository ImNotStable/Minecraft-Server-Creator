package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CoordsTest {
    @Test
    void coordinatesOutsideTheWorldAreRefused() {
        assertThatThrownBy(() -> Coords.pos("2147483647,64,0")).isInstanceOf(ToolException.class)
                .hasMessageContaining("x=2147483647 is outside the world");
        assertThatThrownBy(() -> Coords.region("w", "0,0,0 0,1048577,0")).isInstanceOf(ToolException.class)
                .hasMessageContaining("y=1048577 is outside the world");
        assertThatThrownBy(() -> Coords.area("w", "0,0 0,-30000001")).isInstanceOf(ToolException.class)
                .hasMessageContaining("z=-30000001");
    }

    @Test
    void theWorldEdgesThemselvesAreAccepted() {
        assertThat(Coords.pos("-30000000,-1048576,30000000")).isEqualTo(new BlockPos(-30_000_000, -1_048_576, 30_000_000));
        assertThat(Coords.area("w", "30000000,0 -30000000,0").sizeX()).isEqualTo(60_000_001);
    }

    @Test
    void inWorldChecksOffsetsComputedInLong() {
        assertThat(Coords.inWorld(1, 2, 3)).isEqualTo(new BlockPos(1, 2, 3));
        assertThatThrownBy(() -> Coords.inWorld(30_000_000L * 1023, 0, 0)).isInstanceOf(ToolException.class)
                .hasMessageContaining("outside the world");
    }

    @Test
    @Timeout(5)
    void splitEndsAtTheLargestInt() {
        assertThat(Coords.split(Region.of("w", 2147483520, 0, 0, 2147483647, 100, 100)))
                .hasSize(2).allSatisfy(r -> assertThat(r.max().x()).isEqualTo(Integer.MAX_VALUE));
    }
}
