package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Reads a region into a palette grid, in slices. Cells above or below the world read as void air. */
final class ReadJob implements SlicedJob<BlockGrid> {
    static final String OUTSIDE_WORLD = "minecraft:void_air";
    private static final int CHECK_EVERY = 1024;

    private final World world;
    private final Region region;
    private final int[] blocks;
    private final Map<BlockData, Integer> index = new HashMap<>();
    private final List<String> palette = new ArrayList<>();
    private final int minHeight;
    private final int maxHeight;
    private int outsideId = -1;
    private int next;

    ReadJob(World world, Region region) {
        this.world = world;
        this.region = region;
        this.blocks = new int[Math.toIntExact(region.volume())];
        this.minHeight = world.getMinHeight();
        this.maxHeight = world.getMaxHeight();
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
            blocks[next] = y < minHeight || y >= maxHeight ? outside() : id(world.getBlockData(x, y, z));
            next++;
            if (next % CHECK_EVERY == 0 && System.nanoTime() - deadlineNanos > 0) {
                return false;
            }
        }
        return true;
    }

    private int id(BlockData data) {
        Integer id = index.get(data);
        if (id == null) {
            id = palette.size();
            palette.add(data.getAsString());
            index.put(data, id);
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
