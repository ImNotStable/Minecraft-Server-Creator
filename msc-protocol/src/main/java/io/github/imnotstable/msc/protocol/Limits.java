package io.github.imnotstable.msc.protocol;

import java.time.Duration;

/** Hard limits that both sides enforce. */
public final class Limits {
    /** Largest frame either side accepts, in bytes of UTF-8. */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    /** Largest region a single read or write may cover, in blocks (128 x 128 x 64). */
    public static final long MAX_REGION_VOLUME = 128L * 128L * 64L;

    /** Longest the agent waits once, for the main thread or for chunks to load, before it answers {@code timeout}. */
    public static final Duration AGENT_WAIT = Duration.ofMinutes(2);

    /**
     * Most such waits one operation makes: on Minestom a read, write or undo waits for its chunks, then for one
     * main-thread job (the edit, or the whole read with its entities and signs).
     */
    public static final int AGENT_WAITS_PER_OP = 2;

    /**
     * How long the core waits for an answer: past every wait the agent may make, so the agent's answer or its
     * timeout error always arrives first and the undo id of a write's changed blocks is never lost.
     */
    public static final Duration CORE_CALL_TIMEOUT = AGENT_WAIT.multipliedBy(AGENT_WAITS_PER_OP).plusSeconds(10);

    /** Environment variable the core sets for the agent: {@code host:port} to connect to. */
    public static final String ENV_CORE_ADDRESS = "MSC_CORE_ADDRESS";

    /** Environment variable the core sets for the agent: the shared session token. */
    public static final String ENV_TOKEN = "MSC_TOKEN";

    private Limits() {
    }
}
