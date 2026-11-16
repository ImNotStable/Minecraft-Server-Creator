package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;

/**
 * A whole {@code world.read} in one main-thread job: the grid in slices, then the entities and sign text. One job is
 * one agent wait, so a read stays within {@link io.github.imnotstable.msc.protocol.Limits#AGENT_WAITS_PER_OP} (#289).
 *
 * <p>The extras run in the step that finishes the grid when the deadline allows, and otherwise in the next step
 * whatever the deadline, as one bounded step. A read without extras finishes with its grid. So a read never waits
 * for a spare budget that jobs queued before it may use up every tick (#310).
 */
final class WorldReadJob<S> implements SlicedJob<Ops.ReadResult> {
    private final AgentWorld<S> world;
    private final Region region;
    private final Ops.ReadParams params;
    private final ReadJob<S> grid;
    private boolean extrasDeferred;
    private Ops.ReadResult result;

    WorldReadJob(AgentWorld<S> world, Ops.ReadParams params) {
        this.world = world;
        this.region = params.region();
        this.params = params;
        this.grid = new ReadJob<>(world, region, params.blockData());
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (!extrasDeferred) {
            if (!grid.step(deadlineNanos)) {
                return false;
            }
            boolean extras = params.includeEntities() || params.includeSigns();
            if (extras && System.nanoTime() - deadlineNanos > 0) {
                extrasDeferred = true; // the grid used this step; the extras run in the next one, deadline or not
                return false;
            }
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
