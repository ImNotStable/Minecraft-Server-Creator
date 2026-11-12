package io.github.imnotstable.msc.agent.fabric;

import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Applies a {@link ChangeSource} to a world in slices. Blocks already in the target state are
 * skipped, so {@code changed} counts real changes and the undo record stays small.
 */
final class EditJob implements SlicedJob<UndoRecord> {
    private static final int CHECK_EVERY = 256;

    private final AgentLevel level;
    private final ChangeSource changes;
    private final boolean physics;
    private final UndoRecord.Builder undo;
    private UndoRecord result;

    EditJob(AgentLevel level, ChangeSource changes, boolean physics) {
        this.level = level;
        this.changes = changes;
        this.physics = physics;
        this.undo = new UndoRecord.Builder(level.name(), changes.size());
    }

    @Override
    public boolean step(long deadlineNanos) {
        int n = 0;
        while (changes.advance()) {
            int x = changes.x();
            int y = changes.y();
            int z = changes.z();
            BlockState target = changes.target();
            BlockState current = level.get(x, y, z);
            if (current != target) {
                undo.add(x, y, z, current);
                level.set(x, y, z, target, physics);
            }
            if (++n % CHECK_EVERY == 0 && System.nanoTime() - deadlineNanos > 0) {
                return false;
            }
        }
        result = undo.build();
        return true;
    }

    @Override
    public UndoRecord result() {
        return result;
    }
}
