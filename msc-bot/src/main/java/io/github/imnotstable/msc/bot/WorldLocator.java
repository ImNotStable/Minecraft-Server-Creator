package io.github.imnotstable.msc.bot;

import io.github.imnotstable.msc.protocol.world.BlockPos;

/**
 * Says which world a player is in; the core asks the server's agent. The client cannot tell itself: two Minestom
 * instances of one dimension look the same to it, and their names are the agent's. Shared with bots for older
 * protocols, so it uses only JDK and msc-protocol types.
 */
@FunctionalInterface
public interface WorldLocator {
    /** The agent's name for the world where {@code player} stands near {@code at}, or null when no world lists it. */
    String locate(String player, BlockPos at) throws Exception;
}
