package io.github.imnotstable.msc.agent.fabric;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;

/** World reads and edits. Every method may be called from any thread; world access goes through {@link MainThread}. */
final class WorldOps {
    /** Most entities one read returns. */
    static final int MAX_ENTITIES = 1000;

    private final Levels levels;
    private final MainThread main;
    private final EditHistory history;

    WorldOps(Levels levels, MainThread main, EditHistory history) {
        this.levels = levels;
        this.main = main;
        this.history = history;
    }

    // ---- reads ----------------------------------------------------------------------------

    Ops.ReadResult read(Ops.ReadParams params) {
        Region region = withinLimit(params.region());
        AgentLevel world = world(region.world());
        BlockGrid grid = main.run(new ReadJob(world, region));
        List<Ops.EntitySummary> entities = params.includeEntities() ? main.call(() -> world.entities(region, MAX_ENTITIES)) : List.of();
        List<Ops.SignText> signs = params.includeSigns() ? signs(world, grid) : List.of();
        return new Ops.ReadResult(grid, entities, signs);
    }

    /** Text of the signs in a grid; block entities are looked up only where the grid has a sign. */
    private List<Ops.SignText> signs(AgentLevel world, BlockGrid grid) {
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

    Ops.EntitiesResult entities(Ops.EntitiesParams params) {
        AgentLevel world = world(params.region().world());
        String type = params.type() == null || params.type().isBlank() ? null : params.type();
        return Ops.EntitiesResult.of(main.call(() -> world.entityInfos(params.region(), type)), params.limit());
    }

    Ops.HeightmapResult heightmap(Ops.HeightmapParams params) {
        AgentLevel world = world(params.world());
        int minX = Math.min(params.min().x(), params.max().x());
        int minZ = Math.min(params.min().z(), params.max().z());
        int sizeX = Math.abs(params.max().x() - params.min().x()) + 1;
        int sizeZ = Math.abs(params.max().z() - params.min().z()) + 1;
        if ((long) sizeX * sizeZ > Limits.MAX_REGION_VOLUME) {
            throw new OpException(ErrorCodes.TOO_LARGE,
                    "area " + sizeX + "x" + sizeZ + " exceeds " + Limits.MAX_REGION_VOLUME + " columns");
        }
        int[] heights = main.run(new HeightmapJob(world, minX, minZ, sizeX, sizeZ));
        return new Ops.HeightmapResult(minX, minZ, sizeX, sizeZ, heights);
    }

    // ---- edits ----------------------------------------------------------------------------

    Ops.EditResult write(Ops.WriteParams params) {
        BlockGrid grid = params.grid();
        Region region = withinLimit(grid.region());
        AgentLevel world = world(region.world());
        requireBuildHeight(world, region.min().y(), region.max().y());
        List<BlockState> palette = grid.palette().stream().map(BlockStates::parse).toList();
        return apply(world, new GridCursor(region, grid.blocks(), palette), params.updatePhysics());
    }

    Ops.EditResult undo(Ops.UndoParams params) {
        UndoRecord record = history.take(params.editId())
                .orElseThrow(() -> new OpException(ErrorCodes.NOT_FOUND, "no undo kept for edit " + params.editId()));
        AgentLevel world = world(record.world());
        return apply(world, record.restore(), false);
    }

    private Ops.EditResult apply(AgentLevel world, ChangeSource changes, boolean physics) {
        UndoRecord record = main.run(new EditJob(world, changes, physics));
        return new Ops.EditResult(history.add(record), record.size());
    }

    // ---- helpers --------------------------------------------------------------------------

    private AgentLevel world(String name) {
        return levels.byName(name)
                .orElseThrow(() -> new OpException(ErrorCodes.NOT_FOUND, "no world named " + name + "; worlds: " + levels.names()));
    }

    private static Region withinLimit(Region region) {
        if (region.volume() > Limits.MAX_REGION_VOLUME) {
            throw new OpException(ErrorCodes.TOO_LARGE,
                    "region volume " + region.volume() + " exceeds " + Limits.MAX_REGION_VOLUME);
        }
        return region;
    }

    private static void requireBuildHeight(AgentLevel world, int minY, int maxY) {
        if (minY < world.minY() || maxY >= world.maxY()) {
            throw new IllegalArgumentException("y " + minY + ".." + maxY + " is outside the world's build height "
                    + world.minY() + ".." + (world.maxY() - 1));
        }
    }

    // ---- cursors --------------------------------------------------------------------------

    /** Grid cells that are not -1. */
    private static final class GridCursor implements ChangeSource {
        private final Region region;
        private final int[] blocks;
        private final List<BlockState> palette;
        private int i = -1;

        GridCursor(Region region, int[] blocks, List<BlockState> palette) {
            this.region = region;
            this.blocks = blocks;
            this.palette = palette;
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
        public BlockState target() {
            return palette.get(blocks[i]);
        }

        @Override
        public long size() {
            return blocks.length;
        }
    }

}
