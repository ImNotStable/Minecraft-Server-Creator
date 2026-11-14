package io.github.imnotstable.msc.process.rcon;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal Source RCON client, as spoken by Minecraft servers: the only one in the repository (ADR 0032). msc-bench
 * runs its setup commands and checks with it, so the bench works the same with or without the toolset, and msc-core
 * asks a server an earlier core left running to stop with it. Every body, the password included, is UTF-8.
 *
 * <p>How a request is sent. The vanilla server reads each request with a single read of up to {@value #READ} bytes, and
 * closes the connection without running anything when that read does not hold exactly one packet. A packet is
 * {@value #OVERHEAD} bytes of framing plus its body, so a body over {@value #MAX_BODY} bytes would be cut by that read
 * and dropped. Such a command or password is refused here, before anything is sent, with an error that names the
 * limit.
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
 *
 * <p>When a reply goes wrong. Any other failure while sending a command or reading its reply (the server closed the
 * connection, no complete reply within the timeout, a bad packet) closes the connection before the error is thrown,
 * and so does finding bytes nobody asked for waiting before a command: the stream is out of step with the replies,
 * so the next command connects and logs in again instead of reading leftovers (#455). Packets with other ids are
 * skipped only until the command's timeout runs out, counted once for the whole reply.
 */
public final class RconClient implements AutoCloseable {

    /** The largest reply packet a Minecraft server sends, in characters. */
    static final int CHUNK = 4096;
    /** The most bytes the vanilla server reads for one request (its connection loop's single read). */
    public static final int READ = 1460;
    /** Framing bytes per packet: length, id, type and the two closing zero bytes. */
    static final int OVERHEAD = 14;
    /** The longest command or password, in UTF-8 bytes, the vanilla server can take in one read. */
    public static final int MAX_BODY = READ - OVERHEAD;
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
        this.timeoutMillis = millis(timeout, "timeout");
        this.endWaitMillis = Math.min(millis(endWait, "endWait"), timeoutMillis);
    }

    /** A socket timeout in milliseconds: never 0, which a socket takes as "wait forever". */
    private static int millis(Duration duration, String name) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("RCON " + name + " must be positive, was " + duration);
        }
        Duration longest = Duration.ofMillis(Integer.MAX_VALUE);
        return Math.clamp((duration.compareTo(longest) > 0 ? longest : duration).toMillis(), 1, Integer.MAX_VALUE);
    }

    /**
     * Connects and logs in. Throws if the server is unreachable, {@link RconRefusedException} if the password is
     * wrong or too long, and {@link IllegalArgumentException} when {@code timeout} is not positive. Connecting and every read wait at most {@code timeout}.
     */
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
        try {
            checkFits("password", password);
        } catch (IOException e) {
            throw new RconRefusedException(e.getMessage());
        }
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
                    throw new RconRefusedException("RCON login refused (wrong password?)");
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
        checkFits("command", command);
        if (socket != null && stale()) {
            discard(); // bytes nobody asked for: the connection is out of step with its replies
        }
        if (socket == null) {
            open(); // dropped after a failed or long reply
        }
        int id;
        Packet first;
        try {
            id = send(TYPE_COMMAND, command);
            long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
            do {
                first = readBy(deadline);
            } while (first.id() != id || first.type() != TYPE_RESPONSE);
        } catch (SocketTimeoutException e) {
            discard();
            throw new SocketTimeoutException("no RCON reply within " + timeoutMillis + " ms");
        } catch (IOException e) {
            discard();
            throw e;
        }
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
                    return reply.toString();
                }
                if (packet.id() == id && packet.type() == TYPE_RESPONSE) {
                    reply.append(packet.body());
                }
            }
        } catch (IOException e) {
            // No answer to the end marker in time, or the server closed the connection on it: the reply is complete
            // as far as anyone can tell, and the connection is no longer in a known state.
            discard();
            return reply.toString();
        }
    }

    /** Reads the next packet, waiting no later than {@code deadline} ({@link System#nanoTime}) for it to start. */
    private Packet readBy(long deadline) throws IOException {
        long left = Math.ceilDiv(deadline - System.nanoTime(), 1_000_000L);
        if (left <= 0) {
            throw new SocketTimeoutException("no RCON reply in time");
        }
        socket.setSoTimeout((int) Math.min(left, timeoutMillis));
        return read();
    }

    /** True when bytes nobody asked for are waiting, or the connection cannot even say. */
    private boolean stale() {
        try {
            return in.available() > 0;
        } catch (IOException e) {
            return true;
        }
    }

    /** Closes the connection and forgets it, so the next command connects afresh. */
    private void discard() {
        Socket open = socket;
        drop();
        if (open != null) {
            try {
                open.close();
            } catch (IOException e) {
                // closing a broken connection: nothing more to do
            }
        }
    }

    private void drop() {
        socket = null;
        in = null;
        out = null;
    }

    /** Throws, naming the limit, when {@code body} is longer than one vanilla read can take. */
    private static void checkFits(String what, String body) throws IOException {
        int bytes = body.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BODY) {
            throw new IOException("RCON " + what + " is " + bytes + " bytes in UTF-8; a Minecraft server reads at most "
                    + MAX_BODY + " bytes per request (" + READ + " minus " + OVERHEAD + " of framing) and drops a "
                    + "longer one, so it was not sent");
        }
    }

    private int send(int type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        int id = nextId++;
        ByteBuffer buffer = ByteBuffer.allocate(OVERHEAD + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(10 + bytes.length).putInt(id).putInt(type).put(bytes).put((byte) 0).put((byte) 0);
        out.write(buffer.array());
        out.flush();
        return id;
    }

    private Packet read() throws IOException {
        int length;
        byte[] data;
        try {
            length = Integer.reverseBytes(in.readInt());
            if (length < 10 || length > MAX_PACKET) {
                throw new IOException("Bad RCON packet length " + length);
            }
            data = new byte[length];
            in.readFully(data);
        } catch (EOFException e) {
            throw new EOFException("server closed the RCON connection");
        }
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
