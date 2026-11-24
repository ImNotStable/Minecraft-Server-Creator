package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;

/** Spots spread over an area, one per cell, for scatter. */
final class Spots {
    private Spots() {
    }

    /** One per {@code spacing} cell of {@code region}, kept with {@code chance}: x, z and a hash (shared with scatter). */
    static List<long[]> spots(Region region, int spacing, int chance, long seed) {
        List<long[]> out = new ArrayList<>();
        BlockPos lo = region.min();
        BlockPos hi = region.max();
        for (int cz = 0; lo.z() + (long) cz * spacing <= hi.z(); cz++) {
            for (int cx = 0; lo.x() + (long) cx * spacing <= hi.x(); cx++) {
                long h = Noise.hash(seed, cx, cz);
                long x = lo.x() + (long) cx * spacing + (long) (Noise.unit(h) * spacing);
                long z = lo.z() + (long) cz * spacing + (long) (Noise.unit(BlockPattern.mix(h)) * spacing);
                if (x <= hi.x() && z <= hi.z() && Noise.unit(BlockPattern.mix(h + 1)) * 100 < chance) {
                    out.add(new long[] {x, z, BlockPattern.mix(h + 2)});
                }
            }
        }
        return out;
    }
}
