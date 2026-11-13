package io.github.imnotstable.msc.protocol;

import java.time.Duration;

/** Hard limits that both sides enforce. */
public final class Limits {
    /**
     * Largest frame either side accepts, in bytes of UTF-8. Neither side drops a message silently over it: an answer
     * that would not fit is replaced by a small {@code too_large} error for the same request id, and a request or
     * event that would not fit fails its sender at once with
     * {@link io.github.imnotstable.msc.protocol.transport.FrameTooLargeException}.
     */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;

    /**
     * Most characters of text one answer carries where its size depends on the server, not the request: the output
     * of {@code server.command}, and the names and tags of {@code world.entities}. Even text that is all 3-byte
     * UTF-8 stays under {@link #MAX_FRAME_BYTES}. Text past it is cut with a marker saying how much was left out.
     */
    public static final int MAX_RESULT_TEXT_CHARS = 4 * 1024 * 1024;

    /** Longest single text in a result (an entity's name, a scoreboard tag), cut with {@link #cut}. */
    public static final int MAX_TEXT_CHARS = 1024;

    /** Longest sign line in a result: a read carries up to 1000 signs of eight lines. */
    public static final int MAX_SIGN_LINE_CHARS = 256;

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

    /**
     * {@code text} cut to at most {@code maxChars} characters, ending in a marker such as {@code " [cut: 1234 more
     * chars]"} when anything was left out. Cutting a cut text again changes nothing; null stays null.
     *
     * @param maxChars at least 64, so the marker always fits
     */
    public static String cut(String text, int maxChars) {
        if (maxChars < 64) {
            throw new IllegalArgumentException("maxChars must be at least 64, got " + maxChars);
        }
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        // Size the marker for the largest count it can show; a smaller count only leaves the result shorter.
        int keep = maxChars - cutMarker(text.length()).length();
        if (Character.isHighSurrogate(text.charAt(keep - 1))) {
            keep--;
        }
        return text.substring(0, keep) + cutMarker(text.length() - keep);
    }

    private static String cutMarker(int removed) {
        return " [cut: " + removed + " more chars]";
    }
}
