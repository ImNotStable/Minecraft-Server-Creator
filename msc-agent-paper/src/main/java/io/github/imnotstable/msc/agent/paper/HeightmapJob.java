package io.github.imnotstable.msc.agent.paper;

import org.bukkit.HeightMap;
import org.bukkit.World;

/** Highest non-air block per column, Z then X order, in slices. */
final class HeightmapJob implements SlicedJob<int[]> {
    private static final int CHECK_EVERY = 256;

    private final World world;
    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int[] heights;
    private int next;

    HeightmapJob(World world, int minX, int minZ, int sizeX, int sizeZ) {
        this.world = world;
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.heights = new int[Math.multiplyExact(sizeX, sizeZ)];
    }

    @Override
    public boolean step(long deadlineNanos) {
        while (next < heights.length) {
            int x = minX + next % sizeX;
            int z = minZ + next / sizeX;
            heights[next] = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
            next++;
            if (next % CHECK_EVERY == 0 && System.nanoTime() - deadlineNanos > 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int[] result() {
        return heights;
    }
}
