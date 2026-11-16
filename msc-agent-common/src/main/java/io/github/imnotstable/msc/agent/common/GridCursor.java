package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;

/** The cells of a block grid that are not -1, in grid order (X, then Z, then Y). */
final class GridCursor<S> implements ChangeSource<S> {
    private final Region region;
    private final int[] blocks;
    private final List<S> palette;
    private final List<?> data;
    private int i = -1;

    GridCursor(Region region, int[] blocks, List<S> palette) {
        this(region, blocks, palette, null);
    }

    /** @param data per palette entry, the block entity data to merge, null entries for none; null for no data at all */
    GridCursor(Region region, int[] blocks, List<S> palette, List<?> data) {
        this.region = region;
        this.blocks = blocks;
        this.palette = palette;
        this.data = data;
    }

    @Override
    public Object dataPatch() {
        return data == null ? null : data.get(blocks[i]);
    }

    @Override
    public boolean advance() {
        do {
            i++;
        } while (i < blocks.length && blocks[i] < 0);
        return i < blocks.length;
    }

    @Override
    public int x() {
        return region.min().x() + i % region.sizeX();
    }

    @Override
    public int y() {
        return region.min().y() + i / (region.sizeX() * region.sizeZ());
    }

    @Override
    public int z() {
        return region.min().z() + (i % (region.sizeX() * region.sizeZ())) / region.sizeX();
    }

    @Override
    public S target() {
        return palette.get(blocks[i]);
    }

    @Override
    public long size() {
        return blocks.length;
    }
}
