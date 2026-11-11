package io.github.imnotstable.msc.bench.rcon;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal Source RCON client, as spoken by Minecraft servers. Used by checks and setup steps,
 * so the bench works the same with or without the toolset.
 */
public final class RconClient implements AutoCloseable {

    private static final int TYPE_RESPONSE = 0;
    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_AUTH = 3;
    private static final int MAX_PACKET = 1 << 20;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private RconClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Connects and logs in. Throws if the server is unreachable or the password is wrong. */
    public static RconClient connect(String host, int port, String password, Duration timeout) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            RconClient client = new RconClient(socket);
            client.authenticate(password);
            return client;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    private void authenticate(String password) throws IOException {
        int id = send(TYPE_AUTH, password);
        while (true) {
            Packet packet = read();
            if (packet.type() == TYPE_COMMAND) {
                if (packet.id() == -1 || packet.id() != id) {
                    throw new IOException("RCON login refused (wrong password?)");
                }
                return;
            }
        }
    }

    /** Runs one console command and returns the server's reply text. */
    public synchronized String command(String command) throws IOException {
        int id = send(TYPE_COMMAND, command);
        while (true) {
            Packet packet = read();
            if (packet.id() == id && packet.type() == TYPE_RESPONSE) {
                return packet.body();
            }
        }
    }

    private int send(int type, String body) throws IOException {
        int id = nextId++;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(14 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(10 + bytes.length).putInt(id).putInt(type).put(bytes).put((byte) 0).put((byte) 0);
        out.write(buffer.array());
        out.flush();
        return id;
    }

    private Packet read() throws IOException {
        int length = Integer.reverseBytes(in.readInt());
        if (length < 10 || length > MAX_PACKET) {
            throw new IOException("Bad RCON packet length " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int id = buffer.getInt();
        int type = buffer.getInt();
        String body = new String(data, 8, length - 10, StandardCharsets.UTF_8);
        return new Packet(id, type, body);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private record Packet(int id, int type, String body) {
    }
}
