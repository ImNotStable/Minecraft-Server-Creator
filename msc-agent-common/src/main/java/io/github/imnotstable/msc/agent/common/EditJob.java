package io.github.imnotstable.msc.agent.common;

/**
 * Applies a {@link ChangeSource} to a world in slices. Blocks already in the target state are
 * skipped, so {@code changed} counts real changes and the undo record stays small. A job that does not finish (its
 * wait timed out, the server stopped, a block failed) is {@link #stop stopped}, which returns the record of the blocks
 * it did change, so they stay undoable.
 */
public final class EditJob<S> implements SlicedJob<UndoRecord<S>> {
    private static final int CHECK_EVERY = 256;

    private final AgentWorld<S> world;
    private final ChangeSource<S> changes;
    private final boolean physics;
    private final UndoRecord.Builder<S> undo;
    private UndoRecord<S> result;
    private boolean stopped;

    public EditJob(AgentWorld<S> world, ChangeSource<S> changes, boolean physics) {
        this.world = world;
        this.changes = changes;
        this.physics = physics;
        this.undo = new UndoRecord.Builder<>(world.name(), changes.size());
    }

    @Override
    public synchronized boolean step(long deadlineNanos) {
        if (stopped) {
            return true;
        }
        int n = 0;
        while (changes.advance()) {
            int x = changes.x();
            int y = changes.y();
            int z = changes.z();
            S target = changes.target();
            S current = world.get(x, y, z);
            if (!world.sameState(current, target)) {
                undo.add(x, y, z, current, world.blockEntity(x, y, z));
                world.set(x, y, z, target, physics);
                Object data = changes.blockEntity();
                if (data != null) {
                    world.loadBlockEntity(x, y, z, data);
                }
            }
            if (++n % CHECK_EVERY == 0 && System.nanoTime() - deadlineNanos > 0) {
                return false;
            }
        }
        result = undo.build();
        return true;
    }

    /**
     * Stop the job where it is and return the record of every block it changed. Waits for a step in progress on the
     * main thread; later steps change nothing. Returns the full record when the job had finished.
     */
    public synchronized UndoRecord<S> stop() {
        stopped = true;
        if (result == null) {
            result = undo.build();
        }
        return result;
    }

    @Override
    public synchronized UndoRecord<S> result() {
        return result;
    }
}
