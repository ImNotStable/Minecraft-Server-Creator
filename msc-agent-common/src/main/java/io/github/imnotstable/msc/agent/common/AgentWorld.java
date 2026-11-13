package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import java.util.Objects;

/**
 * What the operations need from one world. A platform wraps its world type (a Bukkit
 * {@code World}, a Fabric {@code ServerLevel}, a Minestom {@code Instance}); tests use an
 * in-memory world. Every method except the {@code load*} ones is called on the main thread only.
 *
 * @param <S> the platform's block state type
 */
public interface AgentWorld<S> {
    /** The world's name as the core sees it, for example {@code world} or {@code world_nether}. */
    String name();

    /** Lowest buildable Y, inclusive. */
    int minY();

    /** Top of the build height, exclusive (Paper's {@code getMaxHeight}). */
    int maxY();

    /** The full block at a position, block entity data included where the platform keeps it on the state. */
    S get(int x, int y, int z);

    /** The block at a position for reading only; may leave out block entity data. Defaults to {@link #get}. */
    default S peek(int x, int y, int z) {
        return get(x, y, z);
    }

    /** Set a block; {@code physics} lets neighbours react (sand falls, torches pop off). */
    void set(int x, int y, int z, S state, boolean physics);

    /** Whether writing {@code target} over {@code current} would change nothing. Defaults to {@code equals}. */
    default boolean sameState(S current, S target) {
        return Objects.equals(current, target);
    }

    /** The key two states share when they read as the same palette entry. Defaults to the state itself. */
    default Object paletteKey(S state) {
        return state;
    }

    /** The full block state string, for example {@code minecraft:oak_stairs[facing=east,half=bottom,...]}. */
    String serialize(S state);

    /** Y of the highest non-air block in a column. */
    int surfaceY(int x, int z);

    /** Up to {@code max} entities inside the region. */
    List<Ops.EntitySummary> entities(Region region, int max);

    /** Every entity inside the region with name, tags and health; {@code type} (a full id) narrows it, null for all. */
    List<Ops.EntityInfo> entityInfos(Region region, String type);

    /** The text of the sign at a position, or null when there is no sign or it is blank. */
    Ops.SignText sign(int x, int y, int z);

    /**
     * Makes every chunk of an X-Z area readable and writable before a job runs, blocking until it
     * is. Called off the main thread. For platforms that do not load chunks on access (Minestom);
     * does nothing by default.
     */
    default void loadArea(int minX, int minZ, int maxX, int maxZ) {
    }

    /**
     * As {@link #loadArea}, for a job that only reads. The returned action, run once the job is
     * done (from any thread), releases the chunks this call loaded that nothing else needs, so
     * reads do not pile up chunks on a platform that never unloads them. Defaults to
     * {@link #loadArea} and nothing to release.
     */
    default Runnable loadAreaToRead(int minX, int minZ, int maxX, int maxZ) {
        loadArea(minX, minZ, maxX, maxZ);
        return () -> {
        };
    }

    /** As {@link #loadArea}, for the chunks under a set of {@link PackedPos packed positions}. */
    default void loadPositions(long[] positions) {
    }
}
