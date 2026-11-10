package io.github.imnotstable.msc.protocol.transport;

import java.io.IOException;

/** The connection closed before an answer arrived. */
public class ConnectionClosedException extends IOException {
    public ConnectionClosedException(String message, Throwable cause) {
        super(message, cause);
    }
}
