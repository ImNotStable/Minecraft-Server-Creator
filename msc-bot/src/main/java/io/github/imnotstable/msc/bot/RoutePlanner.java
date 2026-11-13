package io.github.imnotstable.msc.bot;

import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.util.List;

/**
 * Plans a walking route for the {@code walk} action; the core plans it with the same search as
 * {@code world_summary path}. Shared with bots for older protocols, so it uses only JDK and msc-protocol types.
 */
@FunctionalInterface
public interface RoutePlanner {
    /**
     * The route block by block: each entry is {x, y, z, move}, the feet block and how it is reached ({@code 'w'} walk,
     * {@code 'u'} up one, {@code 'd'} drop, {@code 'j'} jump a gap, {@code 'c'} climb or swim); the first entry is
     * the start. Throws with the reason, such as the gap or wall in the way, when there is none.
     *
     * @param world the world the player is in, by the name the agent knows it
     */
    List<int[]> route(String world, BlockPos from, BlockPos to) throws Exception;
}
