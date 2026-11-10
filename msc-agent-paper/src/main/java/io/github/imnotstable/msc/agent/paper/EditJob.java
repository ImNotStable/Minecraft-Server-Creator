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
    private final List<BlockData> onlyReplace;
    private final boolean physics;
    private final UndoRecord.Builder undo;
    private UndoRecord result;

    /**
     * @param onlyReplace when not empty, only blocks matching one of these are replaced; a state
     *                    without properties (for example {@code minecraft:stone}) matches any
     *                    state of that block
     */
    EditJob(World world, ChangeSource changes, List<BlockData> onlyReplace, boolean physics) {
        this.world = world;
        this.changes = changes;
        this.onlyReplace = onlyReplace == null ? List.of() : onlyReplace;
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
            if (!current.equals(target) && replaces(current)) {
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

    private boolean replaces(BlockData current) {
        if (onlyReplace.isEmpty()) {
            return true;
        }
        for (BlockData filter : onlyReplace) {
            if (current.matches(filter)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public UndoRecord result() {
        return result;
    }
}
