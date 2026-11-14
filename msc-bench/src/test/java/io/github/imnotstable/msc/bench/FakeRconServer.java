package io.github.imnotstable.msc.bench;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * Speaks just enough RCON for tests, the way the vanilla server does: login, then a reply per command from a function,
 * split into packets of {@link #CHUNK} characters, and "Unknown request" for packets of any other type.
 */
public final class FakeRconServer implements AutoCloseable {

    /** The vanilla server's largest reply packet, in characters. */
    public static final int CHUNK = 4096;

    private final ServerSocket socket;
    private final String password;
    private final UnaryOperator<String> replies;
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final Thread thread;

    public FakeRconServer(String password, UnaryOperator<String> replies) throws IOException {
        this(0, password, replies);
    }

    /** Listens on {@code port}, or on a free one when it is 0. */
    public FakeRconServer(int port, String password, UnaryOperator<String> replies) throws IOException {
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

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                Thread.ofVirtual().start(() -> serve(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            while (true) {
                int length = Integer.reverseBytes(in.readInt());
                byte[] data = new byte[length];
                in.readFully(data);
                ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                int id = buffer.getInt();
                int type = buffer.getInt();
                String body = new String(data, 8, length - 10, StandardCharsets.UTF_8);
                if (type == 3) {
                    write(out, body.equals(password) ? id : -1, 2, "");
                } else if (type == 2) {
                    commands.add(body);
                    // Like the vanilla server: a long reply goes out in packets of 4096 characters, no end marker.
                    String reply = replies.apply(body);
                    int offset = 0;
                    do {
                        int end = Math.min(reply.length(), offset + CHUNK);
                        write(out, id, 0, reply.substring(offset, end));
                        offset = end;
                    } while (offset < reply.length());
                } else {
                    write(out, id, 0, "Unknown request " + Integer.toHexString(type));
                }
            }
        } catch (IOException e) {
            // client went away
        }
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
