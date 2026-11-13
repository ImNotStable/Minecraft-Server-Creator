package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.Limits;
import java.time.Duration;

/** Settings every agent uses. */
public final class AgentDefaults {
    /** Main-thread time sliced jobs may use per tick. 20 ms of a 50 ms tick keeps the server at 20 TPS. */
    public static final Duration TICK_BUDGET = Duration.ofMillis(20);
    /** Longest one wait for the main thread or for chunks; the core's call timeout covers every wait of an op. */
    public static final Duration OP_TIMEOUT = Limits.AGENT_WAIT;
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Blocks of undo history kept, about 8 full-size edits. */
    public static final long UNDO_BLOCKS = 8 * Limits.MAX_REGION_VOLUME;

    private AgentDefaults() {
    }
}
