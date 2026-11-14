package io.github.imnotstable.msc.process;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A loopback port that stays closed while the test holds it: bound by a socket that never listens, so connections to
 * it are refused and no other process can start listening on it. A port picked as "free" and let go could be taken by
 * anything on the machine before the test uses it, and a fixed port (a default like 25566) can be in use.
 */
public final class ClosedPort implements AutoCloseable {

    private final Socket socket;

    private ClosedPort(Socket socket) {
        this.socket = socket;
    }

    public static ClosedPort reserve() throws IOException {
        return reserve(0);
    }

    /**
     * Holds {@code port} on loopback closed, for a test of a server's default port (25565, 25575) that must not reach
     * a server running on this machine. Throws a {@link java.net.BindException} when something already uses it.
     */
    public static ClosedPort reserve(int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        return new ClosedPort(socket);
    }

    public int port() {
        return socket.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
