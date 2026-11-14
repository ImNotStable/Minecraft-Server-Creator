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
 *
 * <p>How a reply is read. A Minecraft server answers a command with packets of at most 4096 characters,
 * all with the command's id and no end marker. The vanilla server also reads each request with a single read of up
 * to 1460 bytes and closes the connection, without running the command, when that read holds more than one packet.
 * So this client never has two packets in flight:
 * <ol>
 *   <li>It sends the command alone and waits for the first reply packet.</li>
 *   <li>A packet under 4096 payload bytes cannot have been split (a full chunk is at least that many bytes
 *   in UTF-8), so it is the whole reply. That covers nearly every real command, and no further packet is sent.</li>
 *   <li>Only after a full-size packet does it send an empty packet of a type the server does not handle. The server
 *   has already taken the command from the socket by then and answers that one ("Unknown request 0") only after the
 *   last packet of the reply, so the reply is every packet before that answer.</li>
 *   <li>A server that ignores that packet, or closes the connection on it, still gets its command answered: when no
 *   answer comes within the end wait after the last reply packet, or the connection fails, the reply is what was read
 *   so far. The connection is then dropped, so no late packet can reach the next command, and the next command
 *   connects and logs in again.</li>
 * </ol>
 */
public final class RconClient implements AutoCloseable {

    /** The largest reply packet a Minecraft server sends, in characters. */
    static final int CHUNK = 4096;
    /** How long to wait for the end marker's answer after the last reply packet before taking the reply as whole. */
    static final Duration END_WAIT = Duration.ofSeconds(2);

    private static final int TYPE_RESPONSE = 0;
    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_AUTH = 3;
    private static final int MAX_PACKET = 1 << 20;

    private final InetSocketAddress address;
    private final String password;
    private final int timeoutMillis;
    private final int endWaitMillis;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private boolean closed;
    private int nextId = 1;

    private RconClient(InetSocketAddress address, String password, Duration timeout, Duration endWait) {
        this.address = address;
        this.password = password;
        this.timeoutMillis = (int) timeout.toMillis();
        this.endWaitMillis = (int) Math.max(1, Math.min(endWait.toMillis(), timeout.toMillis()));
    }

    /** Connects and logs in. Throws if the server is unreachable or the password is wrong. */
    public static RconClient connect(String host, int port, String password, Duration timeout) throws IOException {
        return connect(host, port, password, timeout, END_WAIT);
    }

    /**
     * Like {@link #connect(String, int, String, Duration)}, waiting at most {@code endWait} (and never longer than
     * {@code timeout}) for the end marker's answer after the last packet of a long reply.
     */
    public static RconClient connect(String host, int port, String password, Duration timeout, Duration endWait)
            throws IOException {
        RconClient client = new RconClient(new InetSocketAddress(host, port), password, timeout, endWait);
        client.open();
        return client;
    }

    private void open() throws IOException {
        Socket fresh = new Socket();
        try {
            fresh.connect(address, timeoutMillis);
            fresh.setSoTimeout(timeoutMillis);
            socket = fresh;
            in = new DataInputStream(fresh.getInputStream());
            out = fresh.getOutputStream();
            authenticate();
        } catch (IOException | RuntimeException e) {
            drop();
            fresh.close();
            throw e;
        }
    }

    private void authenticate() throws IOException {
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

    /** Runs one console command and returns the server's whole reply text; see the class comment for how. */
    public synchronized String command(String command) throws IOException {
        if (closed) {
            throw new IOException("RCON client is closed");
        }
        if (socket == null) {
            open(); // dropped after a long reply whose end marker went unanswered
        }
        int id = send(TYPE_COMMAND, command);
        Packet first;
        do {
            first = read();
        } while (first.id() != id || first.type() != TYPE_RESPONSE);
        if (first.size() < CHUNK) {
            return first.body();
        }
        StringBuilder reply = new StringBuilder(first.body());
        try {
            int end = send(TYPE_RESPONSE, "");
            socket.setSoTimeout(endWaitMillis);
            while (true) {
                Packet packet = read();
                if (packet.id() == end) {
                    socket.setSoTimeout(timeoutMillis);
                    return reply.toString();
                }
                if (packet.id() == id && packet.type() == TYPE_RESPONSE) {
                    reply.append(packet.body());
                }
            }
        } catch (IOException e) {
            // No answer to the end marker in time, or the server closed the connection on it: the reply is complete
            // as far as anyone can tell, and the connection is no longer in a known state.
            socket.close();
            drop();
            return reply.toString();
        }
    }

    private void drop() {
        socket = null;
        in = null;
        out = null;
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
        return new Packet(id, type, body, length - 10);
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (socket != null) {
            Socket open = socket;
            drop();
            open.close();
        }
    }

    /** One packet; {@code size} is the body's length in bytes. */
    private record Packet(int id, int type, String body, int size) {
    }
}
