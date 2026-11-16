package io.github.imnotstable.msc.bench;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A loopback port a test hands to a child process that listens on it, such as {@link FakeRconMain}, when the port must
 * be known before the child starts (it goes into a bench config). A port picked with {@code ServerSocket(0)} and closed
 * again is free for every process until the child binds it, and any bind to port 0 can take it first (#580).
 *
 * <p>Held here by a socket that never listens and allows address reuse: the system gives it to no bind to port 0,
 * while the child's server socket, which allows reuse too, can still bind and listen on it. Close it once the child
 * listens. On Windows, where address reuse means something else, the port is let go at once, as before.
 */
public final class HandedPort implements AutoCloseable {

    private final Socket socket;
    private final int port;

    private HandedPort(Socket socket, int port) {
        this.socket = socket;
        this.port = port;
    }

    public static HandedPort reserve() throws IOException {
        Socket socket = new Socket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        int port = socket.getLocalPort();
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            socket.close();
        }
        return new HandedPort(socket, port);
    }

    public int port() {
        return port;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
