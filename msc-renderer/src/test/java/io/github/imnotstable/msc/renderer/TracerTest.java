package io.github.imnotstable.msc.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Rays that start exactly on a cell or grid boundary. */
class TracerTest {
    private static final Look[] STONE = new Looks(TextureSource.none()).resolve(List.of("minecraft:air", "minecraft:stone"));

    private static Tracer tracer(int sx, int sy, int sz, int[] blocks) {
        return new Tracer(sx, sy, sz, blocks, STONE, false, false, false);
    }

    /** Traces one ray and tells whether it showed stone; also checks the shadow walk agrees. */
    private static boolean seesStone(Tracer tracer, double[] o, double[] d) {
        tracer.trace(o[0], o[1], o[2], d[0], d[1], d[2], 0, 0);
        boolean seen = tracer.firstHit == 1;
        assertThat(tracer.blocked(o[0], o[1], o[2], d[0], d[1], d[2], Double.POSITIVE_INFINITY, null))
                .as("shadow walk agrees").isEqualTo(seen);
        return seen;
    }

    @Test
    void aRayOnTheGridsOuterBoundaryMovingOutSeesNothing() {
        Tracer tracer = tracer(1, 1, 1, new int[] {1});
        // The centre of each face of the one stone block, and the outward normal there.
        double[][] origins = {{0, 0.5, 0.5}, {1, 0.5, 0.5}, {0.5, 0, 0.5}, {0.5, 1, 0.5}, {0.5, 0.5, 0}, {0.5, 0.5, 1}};
        double[][] normals = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        for (int face = 0; face < 6; face++) {
            double[] n = normals[face];
            // Straight out, and tilted so the other axes move too.
            double[] out = n;
            double[] tilted = Tracer.unit(n[0] + 0.3 * (1 - Math.abs(n[0])), n[1] + 0.2 * (1 - Math.abs(n[1])),
                    n[2] + 0.1 * (1 - Math.abs(n[2])));
            double[] in = {-n[0], -n[1], -n[2]};
            assertThat(seesStone(tracer, origins[face], out)).as("face %d, straight out", face).isFalse();
            assertThat(seesStone(tracer, origins[face], tilted)).as("face %d, tilted out", face).isFalse();
            assertThat(seesStone(tracer, origins[face], in)).as("face %d, looking in", face).isTrue();
        }
    }

    @Test
    void aRayOnAnOuterCornerMovingOutSeesNothing() {
        Tracer tracer = tracer(1, 1, 1, new int[] {1});
        assertThat(seesStone(tracer, new double[] {1, 1, 1}, Tracer.unit(1, 1, 1))).isFalse();
        assertThat(seesStone(tracer, new double[] {0, 0, 0}, Tracer.unit(-1, -0.5, -0.2))).isFalse();
        assertThat(seesStone(tracer, new double[] {0, 0, 0}, Tracer.unit(1, 1, 1))).isTrue();
    }

    @Test
    void aRayOnACellBoundaryStartsInTheCellItMovesIntoEvenWhenItBarelyMoves() {
        // Two cells stacked: air at y 0, stone at y 1. The ray starts on the plane between them.
        Tracer tracer = tracer(1, 2, 1, new int[] {0, 1});
        double[] o = {0.5, 1, 0.5};
        assertThat(seesStone(tracer, o, Tracer.unit(1, -1e-8, 0))).as("barely down, away from the stone").isFalse();
        assertThat(seesStone(tracer, o, Tracer.unit(1, 1e-8, 0))).as("barely up, into the stone").isTrue();
        assertThat(seesStone(tracer, o, Tracer.unit(1, -0.5, 0))).as("down").isFalse();
        assertThat(seesStone(tracer, o, Tracer.unit(1, 0.5, 0))).as("up").isTrue();
    }

    @Test
    void aRayOnAPartialBlocksSurfaceHitsItOnlyWhenMovingIntoIt() {
        Look[] slab = new Looks(TextureSource.none()).resolve(List.of("minecraft:air", "minecraft:oak_slab[type=bottom]"));
        Tracer tracer = new Tracer(1, 2, 1, new int[] {1, 0}, slab, false, false, false);
        // On the slab's top surface, half way up the cell.
        double[] o = {0.5, 0.5, 0.5};
        for (double[] d : new double[][] {{0, -1, 0}, Tracer.unit(0.3, -1, 0.2)}) {
            tracer.trace(o[0], o[1], o[2], d[0], d[1], d[2], 0, 0);
            assertThat(tracer.firstHit).as("looking down").isEqualTo(1);
        }
        for (double[] d : new double[][] {{0, 1, 0}, Tracer.unit(0.3, 1, 0.2), {1, 0, 0}}) {
            tracer.trace(o[0], o[1], o[2], d[0], d[1], d[2], 0, 0);
            assertThat(tracer.firstHit).as("looking up or level").isEqualTo(-1);
        }
        // Inside the slab it looks out of it.
        tracer.trace(0.5, 0.25, 0.5, 0, -1, 0, 0, 0);
        assertThat(tracer.firstHit).as("inside").isEqualTo(-1);
    }
}
