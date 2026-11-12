package io.github.imnotstable.msc.agent.common;

import java.util.List;
import java.util.Optional;

/**
 * The server's worlds by name, and the block state format they share.
 *
 * @param <S> the platform's block state type
 */
public interface AgentWorlds<S> {
    /** Every world's name, main world first. */
    List<String> names();

    Optional<? extends AgentWorld<S>> byName(String name);

    /**
     * Parses a block state string such as {@code minecraft:oak_stairs[facing=east]}.
     *
     * @throws IllegalArgumentException for an unknown block, property or value, saying which
     */
    S parse(String state);
}
