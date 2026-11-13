package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;

/**
 * A whole {@code world.read} in one main-thread job: the grid in slices, then the entities and sign text on the
 * next tick. One job is one agent wait, so a read stays within {@link io.github.imnotstable.msc.protocol.Limits#AGENT_WAITS_PER_OP}
 * (#289).
 */
final class WorldReadJob<S> implements SlicedJob<Ops.ReadResult> {
    private final AgentWorld<S> world;
    private final Region region;
    private final Ops.ReadParams params;
    private final ReadJob<S> grid;
    private Ops.ReadResult result;

    WorldReadJob(AgentWorld<S> world, Ops.ReadParams params) {
        this.world = world;
        this.region = params.region();
        this.params = params;
        this.grid = new ReadJob<>(world, region);
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (!grid.step(deadlineNanos)) {
            return false;
        }
        if (System.nanoTime() - deadlineNanos > 0) {
            return false; // the grid used this tick; the extras run on the next one
        }
        BlockGrid blocks = grid.result();
        List<Ops.EntitySummary> entities = params.includeEntities()
                ? world.entities(region, WorldOps.MAX_ENTITIES) : List.of();
        List<Ops.SignText> signs = params.includeSigns() ? signs(blocks) : List.of();
        result = new Ops.ReadResult(blocks, entities, signs);
        return true;
    }

    /** Text of the signs in a grid; block entities are looked up only where the grid has a sign. */
    private List<Ops.SignText> signs(BlockGrid blocks) {
        List<Ops.SignText> out = new ArrayList<>();
        for (BlockPos p : Ops.SignText.positions(blocks)) {
            Ops.SignText text = world.sign(p.x(), p.y(), p.z());
            if (text != null) {
                out.add(text);
            }
        }
        return out;
    }

    @Override
    public Ops.ReadResult result() {
        return result;
    }
}
