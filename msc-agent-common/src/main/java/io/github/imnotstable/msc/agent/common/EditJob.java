package io.github.imnotstable.msc.agent.common;

/**
 * Applies a {@link ChangeSource} to a world in slices. Blocks already in the target state are
 * skipped, so {@code changed} counts real changes and the undo record stays small.
 */
public final class EditJob<S> implements SlicedJob<UndoRecord<S>> {
    private static final int CHECK_EVERY = 256;

    private final AgentWorld<S> world;
    private final ChangeSource<S> changes;
    private final boolean physics;
    private final UndoRecord.Builder<S> undo;
    private UndoRecord<S> result;

    public EditJob(AgentWorld<S> world, ChangeSource<S> changes, boolean physics) {
        this.world = world;
        this.changes = changes;
        this.physics = physics;
        this.undo = new UndoRecord.Builder<>(world.name(), changes.size());
    }

    @Override
    public boolean step(long deadlineNanos) {
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

    @Override
    public UndoRecord<S> result() {
        return result;
    }
}
