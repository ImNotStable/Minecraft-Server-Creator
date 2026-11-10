package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.message.ProtocolError;

/**
 * An operation failed. Thrown by handlers to send a specific error code, and used to fail the
 * caller's future when the other side answers with an error.
 */
public class OpException extends RuntimeException {
    private final String code;

    public OpException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public OpException(ProtocolError error) {
        this(error.code(), error.message());
    }

    public String code() {
        return code;
    }

    /** Message without the code prefix. */
    public String detail() {
        String m = getMessage();
        return m.substring(code.length() + 2);
    }
}
