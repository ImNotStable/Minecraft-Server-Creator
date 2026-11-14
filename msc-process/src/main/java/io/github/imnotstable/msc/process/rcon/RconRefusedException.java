package io.github.imnotstable.msc.process.rcon;

import java.io.IOException;

/**
 * The server answered but the login cannot succeed: it refused the password, or the password is too long to send.
 * Trying again does not help, unlike a connection that is refused or times out because the server is not up yet.
 */
public final class RconRefusedException extends IOException {
    private static final long serialVersionUID = 1L;

    public RconRefusedException(String message) {
        super(message);
    }
}
