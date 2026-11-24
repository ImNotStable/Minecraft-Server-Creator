package io.github.imnotstable.msc.agent.common;

/**
 * The chunks a world job touches, for a server that runs each part of a world on its own thread (Folia, ADR 0100): the
 * job must run on the thread that owns all of them.
 */
public record ChunkArea(String world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
    /** The chunks under a block area. */
    public static ChunkArea ofBlocks(String world, int minX, int minZ, int maxX, int maxZ) {
        return new ChunkArea(world, Math.min(minX, maxX) >> 4, Math.min(minZ, maxZ) >> 4, Math.max(minX, maxX) >> 4,
                Math.max(minZ, maxZ) >> 4);
    }

    /** The chunks under a set of {@link PackedPos packed positions}; one chunk at 0,0 when there are none. */
    public static ChunkArea ofPositions(String world, long[] positions) {
        if (positions.length == 0) {
            return new ChunkArea(world, 0, 0, 0, 0);
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long p : positions) {
            minX = Math.min(minX, PackedPos.x(p));
            maxX = Math.max(maxX, PackedPos.x(p));
            minZ = Math.min(minZ, PackedPos.z(p));
            maxZ = Math.max(maxZ, PackedPos.z(p));
        }
        return ofBlocks(world, minX, minZ, maxX, maxZ);
    }

    public int centerChunkX() {
        return (minChunkX + maxChunkX) >> 1;
    }

    public int centerChunkZ() {
        return (minChunkZ + maxChunkZ) >> 1;
    }

    public long chunks() {
        return ((long) maxChunkX - minChunkX + 1) * ((long) maxChunkZ - minChunkZ + 1);
    }
}
