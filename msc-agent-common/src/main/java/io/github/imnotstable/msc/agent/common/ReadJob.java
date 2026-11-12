package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads a region into a palette grid, in slices. Cells above or below the world read as void air. */
public final class ReadJob<S> implements SlicedJob<BlockGrid> {
    public static final String OUTSIDE_WORLD = "minecraft:void_air";
    private static final int CHECK_EVERY = 1024;

    private final AgentWorld<S> world;
    private final Region region;
    private final int[] blocks;
    private final Map<Object, Integer> index = new HashMap<>();
    private final List<String> palette = new ArrayList<>();
    private final int minHeight;
    private final int maxHeight;
    private int outsideId = -1;
    private int next;

    public ReadJob(AgentWorld<S> world, Region region) {
        this.world = world;
        this.region = region;
        this.blocks = new int[Math.toIntExact(region.volume())];
        this.minHeight = world.minY();
        this.maxHeight = world.maxY();
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
            blocks[next] = y < minHeight || y >= maxHeight ? outside() : id(world.peek(x, y, z));
            next++;
            if (next % CHECK_EVERY == 0 && System.nanoTime() - deadlineNanos > 0) {
                return false;
            }
        }
        return true;
    }

    private int id(S state) {
        Object key = world.paletteKey(state);
        Integer id = index.get(key);
        if (id == null) {
            id = palette.size();
            palette.add(world.serialize(state));
            index.put(key, id);
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
