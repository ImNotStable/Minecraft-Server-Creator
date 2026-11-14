package io.github.imnotstable.msc.process.rcon;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Speaks just enough RCON for tests, the way the vanilla server does: login, then a reply per command from a function,
 * split into packets of {@link #CHUNK} characters, and "Unknown request" for packets of any other type.
 *
 * <p>It reads requests like the vanilla connection loop too: one read of up to {@link #READ} bytes per request, and
 * the connection is closed, without running anything, when that read is under 10 bytes or does not hold exactly the
 * one packet its length field announces (two packets that arrived together, for example). {@link Quirks} make it come
 * to that read late, as a busy server does, or treat packets of other types like other servers might.
 */
public final class FakeRconServer implements AutoCloseable {

    /** The vanilla server's largest reply packet, in characters. */
    public static final int CHUNK = 4096;
    /** The vanilla server's read size per request, in bytes. */
    public static final int READ = 1460;

    /** What the server does with a packet that is neither a login nor a command. */
    public enum Other { ANSWER, IGNORE, CLOSE }

    /**
     * Deviations from the plain vanilla behaviour. {@code lateReads} makes the server wait a moment after the first
     * byte of a request arrives before it reads, so whatever the client sent right behind it lands in the same read.
     */
    public record Quirks(boolean lateReads, Other other) {
        public static final Quirks VANILLA = new Quirks(false, Other.ANSWER);
    }

    private static final long LATE_READ_MILLIS = 50;

    private final ServerSocket socket;
    private final String password;
    private final UnaryOperator<String> replies;
    private final Quirks quirks;
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final Thread thread;

    public FakeRconServer(String password, UnaryOperator<String> replies) throws IOException {
        this(0, password, replies);
    }

    /** Listens on {@code port}, or on a free one when it is 0. */
    public FakeRconServer(int port, String password, UnaryOperator<String> replies) throws IOException {
        this(port, password, replies, Quirks.VANILLA);
    }

    public FakeRconServer(String password, UnaryOperator<String> replies, Quirks quirks) throws IOException {
        this(0, password, replies, quirks);
    }

    private FakeRconServer(int port, String password, UnaryOperator<String> replies, Quirks quirks)
            throws IOException {
        this.quirks = quirks;
        this.socket = new ServerSocket(port, 10, InetAddress.getLoopbackAddress());
        this.password = password;
        this.replies = replies;
        this.thread = Thread.ofVirtual().start(this::acceptLoop);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public List<String> commands() {
        return commands;
    }

    /** How many connections the server has accepted. */
    public int connections() {
        return connections.get();
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                connections.incrementAndGet();
                Thread.ofVirtual().start(() -> serve(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            PushbackInputStream in = new PushbackInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            byte[] data = new byte[READ];
            while (true) {
                int read = readRequest(in, data);
                if (read < 10) {
                    return;
                }
                ByteBuffer buffer = ByteBuffer.wrap(data, 0, read).order(ByteOrder.LITTLE_ENDIAN);
                int length = buffer.getInt();
                if (length != read - 4) {
                    return; // like vanilla: anything but exactly one packet in the read closes the connection
                }
                int id = buffer.getInt();
                int type = buffer.getInt();
                String body = new String(data, 12, length - 10, StandardCharsets.UTF_8);
                if (type == 3) {
                    write(out, body.equals(password) ? id : -1, 2, "");
                } else if (type == 2) {
                    commands.add(body);
                    // Like the vanilla server: a long reply goes out in packets of 4096 characters, no end marker.
                    // All in one write, so a client's short end wait (#427) never runs out between two packets.
                    String reply = replies.apply(body);
                    ByteArrayOutputStream packets = new ByteArrayOutputStream();
                    int offset = 0;
                    do {
                        int end = Math.min(reply.length(), offset + CHUNK);
                        write(packets, id, 0, reply.substring(offset, end));
                        offset = end;
                    } while (offset < reply.length());
                    packets.writeTo(out);
                    out.flush();
                } else if (quirks.other() == Other.ANSWER) {
                    write(out, id, 0, "Unknown request " + Integer.toHexString(type));
                } else if (quirks.other() == Other.CLOSE) {
                    return;
                }
            }
        } catch (IOException e) {
            // client went away
        }
    }

    /** One vanilla read: a fresh buffered stream and a single {@code read} of up to {@link #READ} bytes. */
    private int readRequest(PushbackInputStream in, byte[] data) throws IOException {
        if (quirks.lateReads()) {
            int first = in.read();
            if (first < 0) {
                return -1;
            }
            in.unread(first);
            try {
                Thread.sleep(LATE_READ_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        InputStream buffered = new BufferedInputStream(in);
        return buffered.read(data, 0, READ);
    }

    private static void write(OutputStream out, int id, int type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(14 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(10 + bytes.length).putInt(id).putInt(type).put(bytes).put((byte) 0).put((byte) 0);
        out.write(buffer.array());
        out.flush();
    }

    /**
     * Closes the listener and waits for the accept thread to leave {@code accept()}: a virtual thread parked there
     * holds the port until it wakes, so without the wait a new server could not bind the port yet.
     */
    @Override
    public void close() throws IOException {
        socket.close();
        thread.interrupt();
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
