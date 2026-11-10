package io.github.imnotstable.msc.protocol.message;

/** Error codes used in {@link ProtocolError} and {@link Goodbye}. */
public final class ErrorCodes {
    public static final String UNKNOWN_OP = "unknown_op";
    public static final String INVALID_PARAMS = "invalid_params";
    public static final String NOT_FOUND = "not_found";
    public static final String TOO_LARGE = "too_large";
    public static final String TIMEOUT = "timeout";
    public static final String INTERNAL = "internal";
    public static final String UNAUTHORIZED = "unauthorized";
    public static final String VERSION_MISMATCH = "version_mismatch";
    public static final String BAD_FRAME = "bad_frame";
    public static final String SHUTDOWN = "shutdown";

    private ErrorCodes() {
    }
}
