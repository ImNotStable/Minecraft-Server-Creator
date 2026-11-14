package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
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
    void wideLinesMatchTheFullBrushAtEveryPointAndVisitEachBlockAboutOnce() throws InterruptedException {
        for (long seed = 0; seed < 200; seed++) {
            Random rnd = new Random(seed);
            BlockPos from = new BlockPos(rnd.nextInt(21) - 10, 60 + rnd.nextInt(10), rnd.nextInt(21) - 10);
            int len = rnd.nextInt(41);
            BlockPos to = rnd.nextInt(4) == 0 ? from.offset(len, 0, 0) // straight along an axis, as in the report
                    : from.offset(rnd.nextInt(2 * len + 1) - len, rnd.nextInt(2 * len + 1) - len,
                    rnd.nextInt(2 * len + 1) - len);
            int width = 1 + rnd.nextInt(rnd.nextBoolean() ? 6 : 24);
            List<String> replace = rnd.nextBoolean() ? List.of() : List.of("air");
            Step.Line line = new Step.Line("world", from, to, width, BlockPattern.parse("stone:3,dirt:1", seed), replace);
            Region b = line.bounds();
            Random world = new Random(seed * 31 + 7);
            int[] cells = new int[Math.toIntExact(b.volume())];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = world.nextInt(3) == 0 ? 1 : 0;
            }
            BlockGrid grid = new BlockGrid(b, List.of("minecraft:air", "minecraft:stone"), cells);
            Canvas fast = new Canvas(b, r -> grid);
            line.draw(fast);
            Canvas slow = new Canvas(b, r -> grid);
            stampFullBrushAtEveryPoint(line, slow);
            for (int y = b.min().y(); y <= b.max().y(); y++) {
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    for (int x = b.min().x(); x <= b.max().x(); x++) {
                        assertThat(fast.planned(x, y, z)).as("seed %d %s at %d,%d,%d", seed, line, x, y, z)
                                .isEqualTo(slow.planned(x, y, z));
                    }
                }
            }
            // The work follows the blocks drawn: stamping every brush cell at every point visits a block
            // about width / 2 times, which made a 64-wide line take seconds (#320).
            Set<BlockPos> distinct = new HashSet<>();
            long[] visits = {0};
            Shapes.sweep(Shapes.line(from, to), width, (x, y, z) -> {
                visits[0]++;
                distinct.add(new BlockPos(x, y, z));
            });
            assertThat(visits[0]).as("seed %d %s: %d visits for %d blocks", seed, line, visits[0], distinct.size())
                    .isLessThanOrEqualTo(distinct.size() + distinct.size() / 4);
        }
    }

    /** The plain way to draw a line: the whole brush at every point, as {@link Step.Line} did before #320. */
    private static void stampFullBrushAtEveryPoint(Step.Line line, Canvas c) throws InterruptedException {
        int w = line.width();
        int lo = Shapes.brushLow(w);
        boolean[] brush = Shapes.brush(w);
        Canvas.Mask mask = c.mask(line.bounds(), line.replace());
        for (BlockPos p : Shapes.line(line.from(), line.to())) {
            for (int dy = -lo; dy < w - lo; dy++) {
                for (int dz = -lo; dz < w - lo; dz++) {
                    for (int dx = -lo; dx < w - lo; dx++) {
                        int x = p.x() + dx;
                        int y = p.y() + dy;
                        int z = p.z() + dz;
                        if (brush[((dy + lo) * w + dz + lo) * w + dx + lo] && mask.allows(x, y, z)) {
                            c.set(x, y, z, line.block().at(x, y, z));
                        }
                    }
                }
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

    @Test
    void everyShapeDrawsOnlyInsideItsBoundsAndFillsThemWhenSolid() throws InterruptedException {
        for (long seed = 0; seed < 300; seed++) {
            Random rnd = new Random(seed);
            BlockPos o = new BlockPos(rnd.nextInt(41) - 20, 60 + rnd.nextInt(10), rnd.nextInt(41) - 20);
            int r = rnd.nextInt(7);
            int h = 1 + rnd.nextInt(5);
            BlockPattern mix = BlockPattern.parse("stone:3,dirt:1", seed);
            Step step = switch (rnd.nextInt(9)) {
                case 0 -> new Step.Fill(Region.of("world", o.x(), o.y(), o.z(), o.x() + rnd.nextInt(6) - 3,
                        o.y() + rnd.nextInt(6) - 3, o.z() + rnd.nextInt(6) - 3), mix,
                        Step.Fill.SHAPES.get(rnd.nextInt(4)), List.of());
                case 1 -> new Step.Cylinder("world", o, r, h, rnd.nextBoolean() ? -1 : rnd.nextInt(r + 1) - 1,
                        rnd.nextBoolean() ? 0 : 1 + rnd.nextInt(r + 1), mix, List.of());
                case 2 -> new Step.Sphere("world", o, r, rnd.nextBoolean() ? 0 : 1 + rnd.nextInt(r + 1), rnd.nextBoolean(),
                        mix, List.of());
                case 3 -> new Step.Line("world", o, o.offset(rnd.nextInt(13) - 6, rnd.nextInt(13) - 6, rnd.nextInt(13) - 6),
                        1 + rnd.nextInt(8), mix, List.of());
                case 4 -> new Step.Helix("world", o, 1 + r, h, rnd.nextDouble() * 4 - 2, 1 + rnd.nextInt(r + 2),
                        rnd.nextInt(721) - 360, mix, List.of());
                case 5 -> new Step.Bands("world", o, h, new int[] {r, r + 1 + rnd.nextInt(4)},
                        List.of(STONE, BlockPattern.single("sand")), rnd.nextInt(4), seed, List.of());
                case 6 -> new Step.Set("world", List.of(o, o.offset(rnd.nextInt(9) - 4, rnd.nextInt(9) - 4, 0)),
                        List.of("minecraft:stone", "minecraft:dirt"), List.of());
                case 7 -> new Step.Stamp("world", List.of(new Step.Stamp.Placement(new BlockGrid(
                        Region.of("world", 0, 0, 0, r, h - 1, 2), List.of("minecraft:stone"), new int[(r + 1) * h * 3]), o)),
                        true, List.of());
                default -> new Step.Group(List.of(new Step.Sphere("world", o, r, 0, false, mix, List.of()),
                        new Step.Line("world", o, o.offset(9, 0, 0), 2, mix, List.of())));
            };
            Region b = step.bounds();
            // A canvas wider than the step on every side: anything drawn outside the step's bounds would show.
            Canvas c = new Canvas(Region.of("world", b.min().x() - 9, b.min().y() - 9, b.min().z() - 9, b.max().x() + 9,
                    b.max().y() + 9, b.max().z() + 9), q -> {
                throw new AssertionError("no world read expected");
            });
            step.draw(c);
            Region used = c.used();
            assertThat(used).as("seed %d %s draws nothing", seed, step).isNotNull();
            assertThat(b.contains(used.min().x(), used.min().y(), used.min().z())
                    && b.contains(used.max().x(), used.max().y(), used.max().z()))
                    .as("seed %d: %s drew %s outside its bounds %s", seed, step.getClass().getSimpleName(), used, b).isTrue();
            boolean solid = step instanceof Step.Fill f && !f.shape().equals("walls") || step instanceof Step.Line
                    || step instanceof Step.Stamp || step instanceof Step.Cylinder cy && cy.inner() < 0 && cy.thickness() == 0
                    || step instanceof Step.Sphere sp && sp.thickness() == 0;
            if (solid) {
                // The dry run reports the step's bounds: for these shapes they are exactly the blocks drawn.
                assertThat(used).as("seed %d %s", seed, step.getClass().getSimpleName()).isEqualTo(b);
            }
        }
    }
}
