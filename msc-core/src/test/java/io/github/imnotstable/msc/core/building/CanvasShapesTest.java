package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanvasShapesTest {
    private static final BlockPattern STONE = BlockPattern.single("stone");

    private static Canvas draw(Step... steps) throws InterruptedException {
        Region b = steps[0].bounds();
        for (Step s : steps) {
            Region r = s.bounds();
            b = Region.of("world", Math.min(b.min().x(), r.min().x()), Math.min(b.min().y(), r.min().y()),
                    Math.min(b.min().z(), r.min().z()), Math.max(b.max().x(), r.max().x()),
                    Math.max(b.max().y(), r.max().y()), Math.max(b.max().z(), r.max().z()));
        }
        Canvas c = new Canvas(b, r -> {
            throw new AssertionError("no world read expected");
        });
        for (Step s : steps) {
            s.draw(c);
        }
        return c;
    }

    @Test
    void discsAndRingsAreRoundAndClosed() throws InterruptedException {
        BlockPos o = new BlockPos(0, 64, 0);
        assertThat(draw(new Step.Cylinder("world", o, 2, 1, -1, 0, STONE, List.of())).placed()).isEqualTo(21);
        Canvas ring = draw(new Step.Cylinder("world", o, 10, 3, -1, 1, STONE, List.of()));
        // Closed: walking from the centre along any straight row or column hits the wall.
        for (int d = -10; d <= 10; d++) {
            assertThat(hits(ring, d, true)).isTrue();
            assertThat(hits(ring, d, false)).isTrue();
        }
        assertThat(ring.planned(0, 64, 0)).isNull();
        assertThat(ring.planned(10, 66, 0)).isEqualTo("minecraft:stone");
        // No cell of the ring may be a 4-neighbour of the inside and the outside at once (no gap), and it is thin.
        assertThat(ring.placed()).isLessThan(3 * 2 * 3 * 21);
        Canvas holed = draw(new Step.Cylinder("world", o, 5, 1, 3, 0, STONE, List.of()));
        assertThat(holed.planned(0, 64, 0)).isNull();
        assertThat(holed.planned(3, 64, 0)).isNull();
        assertThat(holed.planned(4, 64, 0)).isEqualTo("minecraft:stone");
    }

    private static boolean hits(Canvas c, int offset, boolean alongX) {
        for (int d = 0; d <= 11; d++) {
            if (c.planned(alongX ? d : offset, 64, alongX ? offset : d) != null) {
                return true;
            }
        }
        return Math.abs(offset) > 10;
    }

    @Test
    void spheresDomesAndShells() throws InterruptedException {
        BlockPos o = new BlockPos(0, 64, 0);
        long solid = draw(new Step.Sphere("world", o, 6, 0, false, STONE, List.of())).placed();
        long shell = draw(new Step.Sphere("world", o, 6, 1, false, STONE, List.of())).placed();
        assertThat(solid).isBetween(1100L, 1250L);
        assertThat(shell).isLessThan(solid / 2);
        Canvas dome = draw(new Step.Sphere("world", o, 6, 1, true, STONE, List.of()));
        assertThat(dome.used().min().y()).isEqualTo(64);
        assertThat(dome.planned(0, 70, 0)).isNotNull();
        assertThat(dome.planned(0, 66, 0)).isNull();
    }

    @Test
    void linesReachBothEndsAndHaveWidth() throws InterruptedException {
        Canvas thin = draw(new Step.Line("world", new BlockPos(0, 64, 0), new BlockPos(10, 69, 3), 1, STONE, List.of()));
        assertThat(thin.placed()).isEqualTo(11);
        assertThat(thin.planned(0, 64, 0)).isNotNull();
        assertThat(thin.planned(10, 69, 3)).isNotNull();
        Canvas wide = draw(new Step.Line("world", new BlockPos(0, 64, 0), new BlockPos(10, 64, 0), 3, STONE, List.of()));
        assertThat(wide.planned(5, 65, 0)).isNotNull();
        assertThat(wide.planned(5, 64, 1)).isNotNull();
        assertThat(wide.planned(5, 66, 0)).isNull();
        assertThat(wide.planned(5, 65, 1)).isNotNull();
    }

    @Test
    void lineWidthIsTheNumberOfBlocksAcrossForEvenAndOddWidths() throws InterruptedException {
        for (int w = 1; w <= 8; w++) {
            Step.Line line = new Step.Line("world", new BlockPos(0, 64, 0), new BlockPos(10, 64, 0), w, STONE, List.of());
            Canvas c = draw(line);
            Region used = c.used();
            assertThat(used.sizeY()).as("w=%d height", w).isEqualTo(w);
            assertThat(used.sizeZ()).as("w=%d depth", w).isEqualTo(w);
            assertThat(used.sizeX()).as("w=%d length", w).isEqualTo(11 + w - 1);
            // The dry run's bounds are the blocks drawn, no wider and no narrower.
            assertThat(line.bounds()).as("w=%d bounds", w).isEqualTo(used);
            // A full row of w blocks crosses the line's middle.
            int lo = (w - 1) / 2;
            for (int dz = -lo; dz < w - lo; dz++) {
                assertThat(c.planned(5, 64, dz)).as("w=%d dz=%d", w, dz).isNotNull();
            }
        }
    }

    @Test
    void bandsGoOutwardAndLaterStepsWin() throws InterruptedException {
        Canvas c = draw(new Step.Bands("world", new BlockPos(0, 63, 0), 1, new int[] {3, 5},
                        List.of(BlockPattern.single("grass_block"), BlockPattern.single("sand")), 0, 0, List.of()),
                new Step.Set("world", List.of(new BlockPos(0, 63, 0)), List.of("minecraft:water"), List.of()));
        assertThat(c.planned(0, 63, 0)).isEqualTo("minecraft:water");
        assertThat(c.planned(3, 63, 0)).isEqualTo("minecraft:grass_block");
        assertThat(c.planned(5, 63, 0)).isEqualTo("minecraft:sand");
        assertThat(c.planned(6, 63, 0)).isNull();
        Canvas jittered = draw(new Step.Bands("world", new BlockPos(0, 63, 0), 1, new int[] {20},
                List.of(BlockPattern.single("sand")), 3, 1, List.of()));
        assertThat(jittered.placed()).isNotEqualTo(draw(new Step.Cylinder("world", new BlockPos(0, 63, 0), 20, 1, -1, 0,
                BlockPattern.single("sand"), List.of())).placed());
    }

    @Test
    void fillShapes() throws InterruptedException {
        Region r = Region.of("world", 0, 64, 0, 4, 67, 4);
        assertThat(draw(new Step.Fill(r, STONE, "solid", List.of())).placed()).isEqualTo(100);
        Canvas hollow = draw(new Step.Fill(r, STONE, "hollow", List.of()));
        assertThat(hollow.planned(2, 65, 2)).isEqualTo("minecraft:air");
        assertThat(hollow.counts()).containsEntry("stone", 100L - 18).containsEntry("air", 18L);
        Canvas walls = draw(new Step.Fill(r, STONE, "walls", List.of()));
        assertThat(walls.planned(2, 64, 2)).isNull();
        assertThat(walls.placed()).isEqualTo(16 * 4);
        assertThat(draw(new Step.Fill(r, STONE, "outline", List.of())).placed()).isEqualTo(100 - 18);
    }
}
