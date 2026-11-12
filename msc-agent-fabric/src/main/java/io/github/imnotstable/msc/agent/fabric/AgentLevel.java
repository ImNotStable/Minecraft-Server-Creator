package io.github.imnotstable.msc.agent.fabric;

import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The block access the operations need from one world. {@link FabricLevel} wraps a
 * {@code ServerLevel}; tests use an in-memory level. Called on the main thread only.
 */
interface AgentLevel {
    /** The world's name as the core sees it, for example {@code world} or {@code world_nether}. */
    String name();

    /** Lowest buildable Y, inclusive. */
    int minY();

    /** Top of the build height, exclusive (Paper's {@code getMaxHeight}). */
    int maxY();

    BlockState get(int x, int y, int z);

    /** Set a block; {@code physics} lets neighbours react (sand falls, torches pop off). */
    void set(int x, int y, int z, BlockState state, boolean physics);

    /** Y of the highest non-air block in a column, or {@code minY() - 1} for an empty column. */
    int surfaceY(int x, int z);

    /** Up to {@code max} entities inside the region. */
    List<Ops.EntitySummary> entities(Region region, int max);

    /** Every entity inside the region with name, tags and health; {@code type} (a full id) narrows it, null for all. */
    List<Ops.EntityInfo> entityInfos(Region region, String type);

    /** The text of the sign at a position, or null when there is no sign or it is blank. */
    Ops.SignText sign(int x, int y, int z);
}
