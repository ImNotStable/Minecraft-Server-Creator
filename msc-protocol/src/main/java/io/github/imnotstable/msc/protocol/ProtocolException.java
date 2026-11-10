package io.github.imnotstable.msc.protocol;

import java.io.IOException;

/** A frame could not be read, parsed or accepted. */
public class ProtocolException extends IOException {
    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
