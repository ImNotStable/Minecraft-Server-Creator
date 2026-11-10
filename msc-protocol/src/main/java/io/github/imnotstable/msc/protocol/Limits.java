package io.github.imnotstable.msc.protocol;

/** Hard limits that both sides enforce. */
public final class Limits {
    /** Largest frame either side accepts, in bytes of UTF-8. */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    /** Largest region a single read or write may cover, in blocks (128 x 128 x 64). */
    public static final long MAX_REGION_VOLUME = 128L * 128L * 64L;

    /** Environment variable the core sets for the agent: {@code host:port} to connect to. */
    public static final String ENV_CORE_ADDRESS = "MSC_CORE_ADDRESS";

    /** Environment variable the core sets for the agent: the shared session token. */
    public static final String ENV_TOKEN = "MSC_TOKEN";

    private Limits() {
    }
}
