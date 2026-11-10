package io.github.imnotstable.msc.protocol.world;

import java.util.List;
import java.util.Objects;

/**
 * Blocks of a box as a palette plus indices, the compact form used for both reads and writes.
 *
 * @param region  the box the grid covers
 * @param palette block states such as {@code minecraft:oak_stairs[facing=north,half=bottom]}
 * @param blocks  one palette index per block, Y-Z-X order (see {@link Region#index}); in writes,
 *                {@code -1} means "leave this block unchanged"
 */
public record BlockGrid(Region region, List<String> palette, int[] blocks) {
    public BlockGrid {
        Objects.requireNonNull(region, "region");
        palette = List.copyOf(palette);
        Objects.requireNonNull(blocks, "blocks");
        if (blocks.length != region.volume()) {
            throw new IllegalArgumentException("expected " + region.volume() + " blocks, got " + blocks.length);
        }
        for (int b : blocks) {
            if (b < -1 || b >= palette.size()) {
                throw new IllegalArgumentException("palette index " + b + " out of range");
            }
        }
    }

    /** Block state at a world position, or {@code null} for a "leave unchanged" cell. */
    public String stateAt(int x, int y, int z) {
        int i = blocks[region.index(x, y, z)];
        return i < 0 ? null : palette.get(i);
    }
}
