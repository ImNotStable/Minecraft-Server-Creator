package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;

/**
 * World reads and edits. Every method may be called from any thread except the main thread of a
 * platform that loads chunks asynchronously; world access goes through {@link MainThread}.
 *
 * @param <S> the platform's block state type
 */
public final class WorldOps<S> {
    /** Most entities one read returns. */
    public static final int MAX_ENTITIES = 1000;

    private final AgentWorlds<S> worlds;
    private final MainThread main;
    private final EditHistory<S> history;

    public WorldOps(AgentWorlds<S> worlds, MainThread main, EditHistory<S> history) {
        this.worlds = worlds;
        this.main = main;
        this.history = history;
    }

    // ---- reads ----------------------------------------------------------------------------

    public Ops.ReadResult read(Ops.ReadParams params) {
        Region region = withinLimit(params.region());
        AgentWorld<S> world = world(region.world());
        Runnable release = world.loadAreaToRead(region.min().x(), region.min().z(), region.max().x(), region.max().z());
        try {
            BlockGrid grid = main.run(new ReadJob<>(world, region));
            List<Ops.EntitySummary> entities = params.includeEntities()
                    ? main.call(() -> world.entities(region, MAX_ENTITIES)) : List.of();
            List<Ops.SignText> signs = params.includeSigns() ? signs(world, grid) : List.of();
            return new Ops.ReadResult(grid, entities, signs);
        } finally {
            release.run();
        }
    }

    /** Text of the signs in a grid; block entities are looked up only where the grid has a sign. */
    private List<Ops.SignText> signs(AgentWorld<S> world, BlockGrid grid) {
        List<BlockPos> at = Ops.SignText.positions(grid);
        if (at.isEmpty()) {
            return List.of();
        }
        return main.call(() -> {
            List<Ops.SignText> out = new ArrayList<>();
            for (BlockPos p : at) {
                Ops.SignText text = world.sign(p.x(), p.y(), p.z());
                if (text != null) {
                    out.add(text);
                }
            }
            return out;
        });
    }

    /** Entities in a region with name, tags and health, sorted by type and then position. */
    public Ops.EntitiesResult entities(Ops.EntitiesParams params) {
        AgentWorld<S> world = world(params.region().world());
        String type = params.type() == null || params.type().isBlank() ? null : params.type();
        return Ops.EntitiesResult.of(main.call(() -> world.entityInfos(params.region(), type)), params.limit());
    }

    public Ops.HeightmapResult heightmap(Ops.HeightmapParams params) {
        AgentWorld<S> world = world(params.world());
        int minX = Math.min(params.min().x(), params.max().x());
        int minZ = Math.min(params.min().z(), params.max().z());
        long spanX = Math.abs((long) params.max().x() - params.min().x()) + 1;
        long spanZ = Math.abs((long) params.max().z() - params.min().z()) + 1;
        // Each span is at most 2^32, so check them alone first: their product could overflow a long.
        if (spanX > Limits.MAX_REGION_VOLUME || spanZ > Limits.MAX_REGION_VOLUME
                || spanX * spanZ > Limits.MAX_REGION_VOLUME) {
            throw new OpException(ErrorCodes.TOO_LARGE,
                    "area " + spanX + "x" + spanZ + " exceeds " + Limits.MAX_REGION_VOLUME + " columns");
        }
        int sizeX = (int) spanX;
        int sizeZ = (int) spanZ;
        Runnable release = world.loadAreaToRead(minX, minZ, minX + sizeX - 1, minZ + sizeZ - 1);
        try {
            int[] heights = main.run(new HeightmapJob(world, minX, minZ, sizeX, sizeZ));
            return new Ops.HeightmapResult(minX, minZ, sizeX, sizeZ, heights);
        } finally {
            release.run();
        }
    }

    // ---- edits ----------------------------------------------------------------------------

    public Ops.EditResult write(Ops.WriteParams params) {
        BlockGrid grid = params.grid();
        Region region = withinLimit(grid.region());
        AgentWorld<S> world = world(region.world());
        requireBuildHeight(world, region.min().y(), region.max().y());
        List<S> palette = grid.palette().stream().map(worlds::parse).toList();
        world.loadArea(region.min().x(), region.min().z(), region.max().x(), region.max().z());
        return apply(world, new GridCursor<>(region, grid.blocks(), palette), params.updatePhysics());
    }

    public Ops.EditResult undo(Ops.UndoParams params) {
        UndoRecord<S> record = history.take(params.editId())
                .orElseThrow(() -> new OpException(ErrorCodes.NOT_FOUND, "no undo kept for edit " + params.editId()));
        AgentWorld<S> world = world(record.world());
        world.loadPositions(record.positions());
        return apply(world, record.restore(), false);
    }

    private Ops.EditResult apply(AgentWorld<S> world, ChangeSource<S> changes, boolean physics) {
        UndoRecord<S> record = main.run(new EditJob<>(world, changes, physics));
        return new Ops.EditResult(history.add(record), record.size());
    }

    // ---- helpers --------------------------------------------------------------------------

    private AgentWorld<S> world(String name) {
        return worlds.byName(name).<AgentWorld<S>>map(w -> w)
                .orElseThrow(() -> new OpException(ErrorCodes.NOT_FOUND, "no world named " + name + "; worlds: " + worlds.names()));
    }

    private static Region withinLimit(Region region) {
        if (region.volume() > Limits.MAX_REGION_VOLUME) {
            throw new OpException(ErrorCodes.TOO_LARGE,
                    "region volume " + region.volume() + " exceeds " + Limits.MAX_REGION_VOLUME);
        }
        return region;
    }

    private static void requireBuildHeight(AgentWorld<?> world, int minY, int maxY) {
        if (minY < world.minY() || maxY >= world.maxY()) {
            throw new IllegalArgumentException("y " + minY + ".." + maxY + " is outside the world's build height "
                    + world.minY() + ".." + (world.maxY() - 1));
        }
    }
}
