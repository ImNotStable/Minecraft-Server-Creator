package io.github.imnotstable.msc.agent.common;

/** Highest non-air block per column, Z then X order, in slices. */
public final class HeightmapJob implements SlicedJob<int[]> {
    /** Small, because a column can cost a scan down from the top on platforms without heightmaps. */
    private static final int CHECK_EVERY = 64;

    private final AgentWorld<?> world;
    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int[] heights;
    private int next;

    public HeightmapJob(AgentWorld<?> world, int minX, int minZ, int sizeX, int sizeZ) {
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
            heights[next] = world.surfaceY(x, z);
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
