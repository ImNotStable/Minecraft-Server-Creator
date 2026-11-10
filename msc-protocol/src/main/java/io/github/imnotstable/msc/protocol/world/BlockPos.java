package io.github.imnotstable.msc.protocol.world;

/** A block position in world coordinates. */
public record BlockPos(int x, int y, int z) {
    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }
}
