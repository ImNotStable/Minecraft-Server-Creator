package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/**
 * Reads a region into a palette grid, in slices. Cells above or below the world read as void air.
 * Every chunk the region touches must be loaded.
 */
final class ReadJob implements SlicedJob<BlockGrid> {
    static final String OUTSIDE_WORLD = "minecraft:void_air";
    private static final int CHECK_EVERY = 1024;

    private final Instance instance;
    private final Region region;
    private final int[] blocks;
    private final Map<Integer, Integer> index = new HashMap<>();
    private final List<String> palette = new ArrayList<>();
    private final int minHeight;
    private final int maxHeight;
    private int outsideId = -1;
    private int next;

    ReadJob(Instance instance, Region region) {
        this.instance = instance;
        this.region = region;
        this.blocks = new int[Math.toIntExact(region.volume())];
        this.minHeight = instance.getCachedDimensionType().minY();
        this.maxHeight = minHeight + instance.getCachedDimensionType().height();
    }

    @Override
    public boolean step(long deadlineNanos) {
        int sx = region.sizeX();
        int sz = region.sizeZ();
        int plane = sx * sz;
        while (next < blocks.length) {
            int y = region.min().y() + next / plane;
            int z = region.min().z() + (next % plane) / sx;
            int x = region.min().x() + next % sx;
            blocks[next] = y < minHeight || y >= maxHeight ? outside()
                    : id(instance.getBlock(x, y, z, Block.Getter.Condition.TYPE));
            next++;
            if (next % CHECK_EVERY == 0 && System.nanoTime() - deadlineNanos > 0) {
                return false;
            }
        }
        return true;
    }

    private int id(Block block) {
        Integer id = index.get(block.stateId());
        if (id == null) {
            id = palette.size();
            palette.add(BlockStates.format(block));
            index.put(block.stateId(), id);
        }
        return id;
    }

    private int outside() {
        if (outsideId < 0) {
            outsideId = palette.size();
            palette.add(OUTSIDE_WORLD);
        }
        return outsideId;
    }

    @Override
    public BlockGrid result() {
        return new BlockGrid(region, palette, blocks);
    }
}
