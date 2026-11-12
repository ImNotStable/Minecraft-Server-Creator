package io.github.imnotstable.msc.agent.paper;

import java.util.List;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * Applies a {@link ChangeSource} to a world in slices. Blocks already in the target state are
 * skipped, so {@code changed} counts real changes and the undo record stays small.
 */
final class EditJob implements SlicedJob<UndoRecord> {
    private static final int CHECK_EVERY = 256;

    private final World world;
    private final ChangeSource changes;
    private final boolean physics;
    private final UndoRecord.Builder undo;
    private UndoRecord result;

    EditJob(World world, ChangeSource changes, boolean physics) {
        this.world = world;
        this.changes = changes;
        this.physics = physics;
        this.undo = new UndoRecord.Builder(world.getName(), changes.size());
    }

    @Override
    public boolean step(long deadlineNanos) {
        int n = 0;
        while (changes.advance()) {
            int x = changes.x();
            int y = changes.y();
            int z = changes.z();
            BlockData target = changes.target();
            BlockData current = world.getBlockData(x, y, z);
            if (!current.equals(target)) {
                undo.add(x, y, z, current);
                world.getBlockAt(x, y, z).setBlockData(target, physics);
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
