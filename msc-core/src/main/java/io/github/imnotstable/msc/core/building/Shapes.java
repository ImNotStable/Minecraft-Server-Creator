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
