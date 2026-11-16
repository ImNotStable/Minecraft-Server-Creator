package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.ProtocolException;

/**
 * A message was not sent because its frame is over the frame limit. Nothing was written, so the connection is still
 * fine; the sender decides what the other side learns instead (see {@code Connection}).
 */
public final class FrameTooLargeException extends ProtocolException {
    private final long bytes;
    private final int limit;

    /** @param what the message, such as {@code "request world.write"} or {@code "frame"} */
    public FrameTooLargeException(String what, long bytes, int limit) {
        super(what + " is " + bytes + " bytes, over the " + limit + "-byte frame limit");
        this.bytes = bytes;
        this.limit = limit;
    }

    /** Size of the frame in bytes of UTF-8. */
    public long bytes() {
        return bytes;
    }

    /** The limit it is over, in bytes. */
    public int limit() {
        return limit;
    }
}
