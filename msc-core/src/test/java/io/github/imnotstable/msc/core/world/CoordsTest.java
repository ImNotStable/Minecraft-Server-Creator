package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import io.github.imnotstable.msc.protocol.Limits;
import java.util.List;
import java.util.Random;
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

    @Test
    void splitPartsTileTheRegionExactly() {
        for (long seed = 0; seed < 200; seed++) {
            Random rnd = new Random(seed);
            // Sides around the part size (128 x 64 x 128), one more and one less, so every border case comes up.
            int sx = side(rnd, 128);
            int sy = side(rnd, 64);
            int sz = side(rnd, 128);
            int x = rnd.nextInt(2000) - 1000;
            int y = rnd.nextInt(400) - 200;
            int z = rnd.nextInt(2000) - 1000;
            Region r = Region.of("w", x, y, z, x + sx - 1, y + sy - 1, z + sz - 1);
            List<Region> parts = Coords.split(r);
            long total = 0;
            for (Region part : parts) {
                assertThat(part.volume()).as("seed %d", seed).isLessThanOrEqualTo(Limits.MAX_REGION_VOLUME);
                assertThat(r.contains(part.min().x(), part.min().y(), part.min().z())
                        && r.contains(part.max().x(), part.max().y(), part.max().z())).as("seed %d", seed).isTrue();
                total += part.volume();
            }
            // Parts inside the region whose volumes add up to it, pairwise disjoint: every block in exactly one part.
            assertThat(total).as("seed %d %s", seed, r).isEqualTo(r.volume());
            for (int i = 0; i < parts.size(); i++) {
                for (int j = i + 1; j < parts.size(); j++) {
                    assertThat(overlap(parts.get(i), parts.get(j))).as("seed %d parts %d %d", seed, i, j).isFalse();
                }
            }
        }
    }

    private static int side(Random rnd, int part) {
        return switch (rnd.nextInt(4)) {
            case 0 -> 1 + rnd.nextInt(3 * part);
            case 1 -> part * (1 + rnd.nextInt(3));
            case 2 -> part * (1 + rnd.nextInt(3)) + 1;
            default -> Math.max(1, part * (1 + rnd.nextInt(3)) - 1);
        };
    }

    private static boolean overlap(Region a, Region b) {
        return a.min().x() <= b.max().x() && b.min().x() <= a.max().x() && a.min().y() <= b.max().y()
                && b.min().y() <= a.max().y() && a.min().z() <= b.max().z() && b.min().z() <= a.max().z();
    }
}
