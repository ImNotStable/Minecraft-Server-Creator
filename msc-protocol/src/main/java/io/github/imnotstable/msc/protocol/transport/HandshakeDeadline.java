package io.github.imnotstable.msc.protocol.transport;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One deadline for a whole handshake. The socket's read timeout bounds each read only, so a peer trickling a byte
 * at a time could hold a handshake open for ever; this closes the socket when the handshake has not finished in
 * time. Exactly one of {@link #finish} and the deadline wins, so a finished connection is never closed by it.
 */
final class HandshakeDeadline {
    private final AtomicBoolean settled = new AtomicBoolean();
    private final Thread timer;

    HandshakeDeadline(Socket socket, Duration timeout) {
        timer = Thread.ofVirtual().name("msc-handshake-deadline").start(() -> {
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException e) {
                return;
            }
            if (settled.compareAndSet(false, true)) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Closing anyway.
                }
            }
        });
    }

    /** Stops the deadline. True when the handshake finished first: the socket stays open. */
    boolean finish() {
        boolean first = settled.compareAndSet(false, true);
        timer.interrupt();
        return first;
    }

    /** {@link #finish}, throwing when the deadline closed the socket first. */
    void finishOrThrow(String what) throws IOException {
        if (!finish()) {
            throw new ConnectionClosedException(what + " did not finish its handshake in time", null);
        }
    }
}
