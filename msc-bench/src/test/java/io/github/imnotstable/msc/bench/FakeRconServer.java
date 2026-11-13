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

/** Speaks just enough RCON for tests: login, then one reply per command from a function. */
public final class FakeRconServer implements AutoCloseable {

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
                } else {
                    commands.add(body);
                    write(out, id, 0, replies.apply(body));
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

    @Override
    public void close() throws IOException {
        socket.close();
        thread.interrupt();
    }
}
