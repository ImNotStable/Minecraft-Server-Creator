package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;

/** Block-grid geometry for the build steps. Masks are flat arrays centred on the origin. */
final class Shapes {
    private Shapes() {
    }

    static Region box(String world, List<BlockPos> positions) {
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (BlockPos p : positions) {
            lo[0] = Math.min(lo[0], p.x());
            lo[1] = Math.min(lo[1], p.y());
            lo[2] = Math.min(lo[2], p.z());
            hi[0] = Math.max(hi[0], p.x());
            hi[1] = Math.max(hi[1], p.y());
            hi[2] = Math.max(hi[2], p.z());
        }
        return Region.of(world, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
    }

    /** Columns within {@code r} of the centre, as a (2r+1)^2 mask indexed [dz][dx]. A cell is in when its centre is closer than r + 0.5. */
    static boolean[] disc(int r) {
        int n = 2 * r + 1;
        boolean[] out = new boolean[n * n];
        double limit = (r + 0.5) * (r + 0.5);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                out[(dz + r) * n + dx + r] = dx * dx + dz * dz < limit;
            }
        }
        return out;
    }

    /**
     * A round brush exactly {@code width} blocks across, as a width^3 mask indexed [dy][dz][dx] over offsets
     * {@code -low(width) .. width - 1 - low(width)} on each axis. Odd widths are {@link #ball} of radius
     * (width - 1) / 2; even widths have their centre half a block off, toward the positive side.
     */
    static boolean[] brush(int width) {
        int lo = brushLow(width);
        double centre = (width - 1) / 2.0 - lo;
        double limit = width * width / 4.0;
        boolean[] out = new boolean[width * width * width];
        for (int y = 0; y < width; y++) {
            for (int z = 0; z < width; z++) {
                for (int x = 0; x < width; x++) {
                    double dx = x - lo - centre;
                    double dy = y - lo - centre;
                    double dz = z - lo - centre;
                    out[(y * width + z) * width + x] = dx * dx + dy * dy + dz * dz < limit;
                }
            }
        }
        return out;
    }

    /** How far a {@link #brush} reaches below its point: {@code (width - 1) / 2}; it reaches {@code width / 2} above. */
    static int brushLow(int width) {
        return (width - 1) / 2;
    }

    /** A (2r+1)^3 ball mask indexed [dy][dz][dx]. */
    static boolean[] ball(int r) {
        int n = 2 * r + 1;
        boolean[] out = new boolean[n * n * n];
        double limit = (r + 0.5) * (r + 0.5);
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    out[((dy + r) * n + dz + r) * n + dx + r] = dx * dx + dy * dy + dz * dz < limit;
                }
            }
        }
        return out;
    }

    /**
     * The outer {@code thickness} cells of a 2D mask: cells that are in, minus what is left after
     * peeling off every cell with a side neighbour outside, {@code thickness} times. The result has no
     * gap a player could walk through.
     */
    static boolean[] shell2d(boolean[] mask, int r, int thickness) {
        int n = 2 * r + 1;
        boolean[] core = mask.clone();
        for (int t = 0; t < thickness; t++) {
            boolean[] next = new boolean[core.length];
            for (int z = 0; z < n; z++) {
                for (int x = 0; x < n; x++) {
                    int i = z * n + x;
                    next[i] = core[i] && x > 0 && x < n - 1 && z > 0 && z < n - 1
                            && core[i - 1] && core[i + 1] && core[i - n] && core[i + n];
                }
            }
            core = next;
        }
        boolean[] out = new boolean[mask.length];
        for (int i = 0; i < mask.length; i++) {
            out[i] = mask[i] && !core[i];
        }
        return out;
    }

    /** {@link #shell2d} in 3D, with the six face neighbours. */
    static boolean[] shell3d(boolean[] mask, int r, int thickness) {
        int n = 2 * r + 1;
        int layer = n * n;
        boolean[] core = mask.clone();
        for (int t = 0; t < thickness; t++) {
            boolean[] next = new boolean[core.length];
            for (int y = 1; y < n - 1; y++) {
                for (int z = 1; z < n - 1; z++) {
                    for (int x = 1; x < n - 1; x++) {
                        int i = (y * n + z) * n + x;
                        next[i] = core[i] && core[i - 1] && core[i + 1] && core[i - n] && core[i + n]
                                && core[i - layer] && core[i + layer];
                    }
                }
            }
            core = next;
        }
        boolean[] out = new boolean[mask.length];
        for (int i = 0; i < mask.length; i++) {
            out[i] = mask[i] && !core[i];
        }
        return out;
    }

    /** Block positions from {@code a} to {@code b}, both included, one per step along the longest axis. */
    static List<BlockPos> line(BlockPos a, BlockPos b) {
        int dx = b.x() - a.x();
        int dy = b.y() - a.y();
        int dz = b.z() - a.z();
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        List<BlockPos> out = new ArrayList<>(steps + 1);
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0 : (double) i / steps;
            out.add(new BlockPos((int) Math.round(a.x() + dx * t), (int) Math.round(a.y() + dy * t),
                    (int) Math.round(a.z() + dz * t)));
        }
        return out;
    }

    /** Receives one block position. */
    @FunctionalInterface
    interface Cell {
        void at(int x, int y, int z);
    }

    /**
     * Every block a {@link #brush} of {@code width} covers as it moves along {@code points}, as {@link #line} makes them.
     * The whole brush is stamped at the first point only; after that, a step of one block in any of the 26 directions
     * reports just the brush cells the step uncovers, so the work follows the blocks drawn, not length * width^3.
     * A block can still be reported more than once when the line bends back over it, so {@code out} must not care.
     */
    static void sweep(List<BlockPos> points, int width, Cell out) {
        int lo = brushLow(width);
        boolean[] brush = brush(width);
        int[] full = offsets(brush, width, 0, 0, 0);
        int[][] leading = new int[27][];
        BlockPos prev = null;
        for (BlockPos p : points) {
            int[] cells = full;
            if (prev != null) {
                int dx = p.x() - prev.x();
                int dy = p.y() - prev.y();
                int dz = p.z() - prev.z();
                if (dx == 0 && dy == 0 && dz == 0) {
                    continue;
                }
                if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && Math.abs(dz) <= 1) {
                    int d = ((dy + 1) * 3 + dz + 1) * 3 + dx + 1;
                    if (leading[d] == null) {
                        leading[d] = offsets(brush, width, dx, dy, dz);
                    }
                    cells = leading[d];
                }
            }
            for (int i = 0; i < cells.length; i += 3) {
                out.at(p.x() + cells[i] - lo, p.y() + cells[i + 1] - lo, p.z() + cells[i + 2] - lo);
            }
            prev = p;
        }
    }

    /**
     * The brush cells, as x, y, z triples counted from the brush's corner, that a brush moved by (dx, dy, dz) covers
     * and the brush before the move did not. A move of (0, 0, 0) gives the whole brush.
     */
    private static int[] offsets(boolean[] brush, int width, int dx, int dy, int dz) {
        int[] out = new int[3 * brush.length];
        int k = 0;
        for (int y = 0; y < width; y++) {
            for (int z = 0; z < width; z++) {
                for (int x = 0; x < width; x++) {
                    if (!brush[(y * width + z) * width + x]) {
                        continue;
                    }
                    boolean moved = dx != 0 || dy != 0 || dz != 0;
                    int px = x + dx;
                    int py = y + dy;
                    int pz = z + dz;
                    boolean coveredBefore = moved && px >= 0 && px < width && py >= 0 && py < width && pz >= 0
                            && pz < width && brush[(py * width + pz) * width + px];
                    if (!coveredBefore) {
                        out[k++] = x;
                        out[k++] = y;
                        out[k++] = z;
                    }
                }
            }
        }
        return java.util.Arrays.copyOf(out, k);
    }

    /** A smooth wobble in [-1, 1] around a circle, different for every seed. */
    static double wobble(double angle, long seed) {
        double sum = 0;
        double norm = 0;
        int[] freq = {3, 5, 8};
        double[] amp = {1.0, 0.6, 0.35};
        for (int k = 0; k < freq.length; k++) {
            double phase = (BlockPattern.mix(seed * 31 + k) >>> 11) * 0x1.0p-53 * 2 * Math.PI;
            sum += amp[k] * Math.sin(freq[k] * angle + phase);
            norm += amp[k];
        }
        return sum / norm;
    }
}
