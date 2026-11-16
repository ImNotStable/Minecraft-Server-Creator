package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a region into a palette grid, in slices. Cells above or below the world read as void air. With block data
 * (ADR 0042), a block that has block entity data gets its own palette entry, its state followed by the SNBT.
 */
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
    private final boolean withData;
    private long dataChars;
    private int outsideId = -1;
    private int next;

    public ReadJob(AgentWorld<S> world, Region region) {
        this(world, region, false);
    }

    /** @param withData give blocks with block entity data their own palette entry with the data */
    public ReadJob(AgentWorld<S> world, Region region, boolean withData) {
        this.withData = withData;
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
            blocks[next] = y < minHeight || y >= maxHeight ? outside() : id(x, y, z, world.peek(x, y, z));
            next++;
            if (next % CHECK_EVERY == 0 && System.nanoTime() - deadlineNanos > 0) {
                return false;
            }
        }
        return true;
    }

    private int id(int x, int y, int z, S state) {
        String data = withData && world.holdsData(state) ? world.blockData(x, y, z) : null;
        Object key = data == null ? world.paletteKey(state) : new WithData(world.paletteKey(state), data);
        Integer id = index.get(key);
        if (id == null) {
            if (data != null) {
                dataChars += data.length();
                if (dataChars > Limits.MAX_BLOCK_DATA_CHARS) {
                    throw new OpException(ErrorCodes.TOO_LARGE, "the block entity data in this read is over "
                            + Limits.MAX_BLOCK_DATA_CHARS + " characters; read a smaller region");
                }
            }
            id = palette.size();
            palette.add(BlockData.join(world.serialize(state), data));
            index.put(key, id);
        }
        return id;
    }

    /** The palette key of a block with block entity data: equal states with equal data share an entry. */
    private record WithData(Object key, String data) {
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
