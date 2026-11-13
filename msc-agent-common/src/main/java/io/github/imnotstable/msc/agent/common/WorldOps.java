package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.op.PartialEdit;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
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
            // One main-thread job for the grid, entities and signs: with the chunk wait, a read makes at most
            // Limits.AGENT_WAITS_PER_OP waits, so the core's timeout never fires first (#289).
            return main.run(new WorldReadJob<>(world, params));
        } finally {
            release.run();
        }
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
        requireUndoableXz(region);
        List<S> palette = grid.palette().stream().map(worlds::parse).toList();
        world.loadArea(region.min().x(), region.min().z(), region.max().x(), region.max().z());
        EditJob<S> job = new EditJob<>(world, new GridCursor<>(region, grid.blocks(), palette), params.updatePhysics());
        try {
            return apply(job);
        } catch (RuntimeException e) {
            throw keepPartial(job, e);
        }
    }

    /**
     * A write that failed after changing blocks keeps them undoable: their record goes into the history and the
     * error names its id ({@link PartialEdit}), so the core can undo them (#275).
     */
    private RuntimeException keepPartial(EditJob<S> job, RuntimeException e) {
        UndoRecord<S> partial = job.stop();
        if (partial.size() == 0) {
            return e;
        }
        String id = history.add(partial);
        if (e instanceof OpException op) {
            return new OpException(op.code(), PartialEdit.note(op.detail(), id));
        }
        if (e instanceof IllegalArgumentException) {
            return new OpException(ErrorCodes.INVALID_PARAMS, PartialEdit.note(String.valueOf(e.getMessage()), id));
        }
        return new OpException(ErrorCodes.INTERNAL, PartialEdit.note(e.getClass().getSimpleName() + ": " + e.getMessage(), id));
    }

    public Ops.EditResult undo(Ops.UndoParams params) {
        UndoRecord<S> record = history.take(params.editId())
                .orElseThrow(() -> new OpException(ErrorCodes.NOT_FOUND, "no undo kept for edit " + params.editId()));
        try {
            AgentWorld<S> world = world(record.world());
            world.loadPositions(record.positions());
            // A failed undo keeps its record instead of a partial one: re-running it is safe (#192).
            return apply(new EditJob<>(world, record.restore(), false));
        } catch (RuntimeException e) {
            // Keep the record so the core's retry undoes the rest (#192).
            history.putBack(params.editId(), record);
            throw e;
        }
    }

    private Ops.EditResult apply(EditJob<S> job) {
        UndoRecord<S> record = main.run(job);
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

    /** Undo records pack X and Z into 26 bits each ({@link PackedPos}); beyond that undo would hit the wrong blocks. */
    private static void requireUndoableXz(Region region) {
        if (region.min().x() < PackedPos.MIN_XZ || region.max().x() > PackedPos.MAX_XZ
                || region.min().z() < PackedPos.MIN_XZ || region.max().z() > PackedPos.MAX_XZ) {
            throw new IllegalArgumentException("x and z must be within " + PackedPos.MIN_XZ + ".." + PackedPos.MAX_XZ
                    + " to be undoable, got " + region.min().x() + ".." + region.max().x() + ", "
                    + region.min().z() + ".." + region.max().z());
        }
    }

    private static void requireBuildHeight(AgentWorld<?> world, int minY, int maxY) {
        if (minY < world.minY() || maxY >= world.maxY()) {
            throw new IllegalArgumentException("y " + minY + ".." + maxY + " is outside the world's build height "
                    + world.minY() + ".." + (world.maxY() - 1));
        }
    }
}
