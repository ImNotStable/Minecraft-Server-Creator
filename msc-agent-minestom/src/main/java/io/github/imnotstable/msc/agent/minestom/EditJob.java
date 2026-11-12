package io.github.imnotstable.msc.agent.minestom;

import java.util.List;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;

/**
 * Applies a {@link ChangeSource} to an instance in slices. Blocks already in the target state are
 * skipped, so {@code changed} counts real changes and the undo record stays small. Every chunk the
 * changes touch must be loaded.
 */
final class EditJob implements SlicedJob<UndoRecord> {
    private static final int CHECK_EVERY = 256;

    private final Instance instance;
    private final ChangeSource changes;
    private final boolean physics;
    private final UndoRecord.Builder undo;
    private UndoRecord result;

    EditJob(Instance instance, String world, ChangeSource changes, boolean physics) {
        this.instance = instance;
        this.changes = changes;
        this.physics = physics;
        this.undo = new UndoRecord.Builder(world, changes.size());
    }

    @Override
    public boolean step(long deadlineNanos) {
        int n = 0;
        while (changes.advance()) {
            int x = changes.x();
            int y = changes.y();
            int z = changes.z();
            Block target = changes.target();
            Block current = instance.getBlock(x, y, z);
            if (current.stateId() != target.stateId()) {
                undo.add(x, y, z, current);
                instance.setBlock(x, y, z, target, physics);
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
