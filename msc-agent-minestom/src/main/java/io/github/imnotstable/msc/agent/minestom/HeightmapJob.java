package io.github.imnotstable.msc.agent.minestom;

import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/**
 * Highest non-air block per column, Z then X order, in slices. Scans down from the top of the
 * highest non-empty section of each chunk, so flat and low worlds stay cheap. A column with no
 * blocks reports one below the world's minimum height. Every chunk the area touches must be loaded.
 */
final class HeightmapJob implements SlicedJob<int[]> {
    private static final int CHECK_EVERY = 64;

    private final Instance instance;
    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int minY;
    private final int maxY;
    private final int[] heights;
    private int next;

    HeightmapJob(Instance instance, int minX, int minZ, int sizeX, int sizeZ) {
        this.instance = instance;
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.minY = instance.getCachedDimensionType().minY();
        this.maxY = minY + instance.getCachedDimensionType().height() - 1;
        this.heights = new int[Math.multiplyExact(sizeX, sizeZ)];
    }

    @Override
    public boolean step(long deadlineNanos) {
        while (next < heights.length) {
            int x = minX + next % sizeX;
            int z = minZ + next / sizeX;
            heights[next] = top(x, z);
            next++;
            if (next % CHECK_EVERY == 0 && System.nanoTime() - deadlineNanos > 0) {
                return false;
            }
        }
        return true;
    }

    private int top(int x, int z) {
        int start = Math.min(maxY, topOfHighestSection(x, z));
        for (int y = start; y >= minY; y--) {
            if (!instance.getBlock(x, y, z, Block.Getter.Condition.TYPE).air()) {
                return y;
            }
        }
        return minY - 1;
    }

    private int topOfHighestSection(int x, int z) {
        var chunk = instance.getChunkAt(x, z);
        if (chunk == null) {
            return maxY;
        }
        for (int section = chunk.getMaxSection() - 1; section >= chunk.getMinSection(); section--) {
            if (chunk.getSection(section).blockPalette().count() > 0) {
                return section * 16 + 15;
            }
        }
        return minY - 1;
    }

    @Override
    public int[] result() {
        return heights;
    }
}
