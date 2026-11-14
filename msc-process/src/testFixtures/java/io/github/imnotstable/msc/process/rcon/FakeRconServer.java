package io.github.imnotstable.msc.process.rcon;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * to that read late, as a busy server does, send replies in pieces, as a real network does, or treat packets of other
 * types like other servers might.
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
     * {@code pieces} sends the login answer and each command reply in pieces, so the client's reads see a packet in
     * parts, the way a long reply from a real server arrives over the network (see {@link #sendInPieces}).
     */
    public record Quirks(boolean lateReads, Other other, boolean pieces) {
        public static final Quirks VANILLA = new Quirks(false, Other.ANSWER);

        public Quirks(boolean lateReads, Other other) {
            this(lateReads, other, false);
        }
    }

    /**
     * A broken answer to one command (#455). {@code CLOSE_MID_PACKET} sends half the reply packet and closes the
     * connection; {@code STALL_MID_PACKET} sends half and then nothing until the client hangs up;
     * {@code JUNK_AFTER_END_ANSWER} answers normally but sends junk bytes right behind the end marker's answer;
     * {@code ENDLESS_OTHER_IDS} sends packets with other ids, one every few milliseconds, until the client hangs up.
     */
    public enum Fault { CLOSE_MID_PACKET, STALL_MID_PACKET, JUNK_AFTER_END_ANSWER, ENDLESS_OTHER_IDS }

    private static final long LATE_READ_MILLIS = 50;
    private static final long OTHER_ID_PAUSE_MILLIS = 5;
    private static final long PIECE_PAUSE_MILLIS = 20;

    private final ServerSocket socket;
    private final String password;
    private final UnaryOperator<String> replies;
    private final Quirks quirks;
    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger open = new AtomicInteger();
    private final Map<String, Fault> faults = new ConcurrentHashMap<>();
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

    /** How many connections are open now, from the server's side. */
    public int openConnections() {
        return open.get();
    }

    /** Answers the next {@code command} with {@code fault}, and later ones normally again. */
    public void fault(String command, Fault fault) {
        faults.put(command, fault);
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
        open.incrementAndGet();
        boolean junk = false;
        try (client) {
            PushbackInputStream in = new PushbackInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            if (quirks.pieces()) {
                client.setTcpNoDelay(true); // each piece goes out when written, not held back for the one before
            }
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
                    send(in, out, List.of(packet(body.equals(password) ? id : -1, 2, "")));
                } else if (type == 2) {
                    commands.add(body);
                    Fault fault = faults.remove(body);
                    if (fault != null && fault != Fault.JUNK_AFTER_END_ANSWER) {
                        misbehave(fault, in, out, id, replies.apply(body));
                        return;
                    }
                    junk = fault == Fault.JUNK_AFTER_END_ANSWER;
                    // Like the vanilla server: a long reply goes out in packets of 4096 characters, no end marker.
                    String reply = replies.apply(body);
                    List<byte[]> packets = new ArrayList<>();
                    int offset = 0;
                    do {
                        int end = Math.min(reply.length(), offset + CHUNK);
                        packets.add(packet(id, 0, reply.substring(offset, end)));
                        offset = end;
                    } while (offset < reply.length());
                    send(in, out, packets);
                } else if (quirks.other() == Other.ANSWER) {
                    byte[] answer = packet(id, 0, "Unknown request " + Integer.toHexString(type));
                    out.write(junk ? concat(answer, "junk!!!!".getBytes(StandardCharsets.US_ASCII)) : answer);
                    out.flush();
                    junk = false;
                } else if (quirks.other() == Other.CLOSE) {
                    return;
                }
            }
        } catch (IOException e) {
            // client went away
        } finally {
            open.decrementAndGet();
        }
    }

    private static void misbehave(Fault fault, InputStream in, OutputStream out, int id, String reply)
            throws IOException {
        byte[] packet = packet(id, 0, reply);
        switch (fault) {
            case CLOSE_MID_PACKET -> out.write(packet, 0, packet.length / 2);
            case STALL_MID_PACKET -> {
                out.write(packet, 0, packet.length / 2);
                out.flush();
                while (in.read() >= 0) {
                    // wait for the client to hang up
                }
            }
            case ENDLESS_OTHER_IDS -> {
                for (int other = id + 1000; ; other++) {
                    out.write(packet(other, 0, "noise"));
                    out.flush();
                    try {
                        Thread.sleep(OTHER_ID_PAUSE_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            case JUNK_AFTER_END_ANSWER -> throw new IllegalArgumentException("answered in serve");
        }
        out.flush();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] all = new byte[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
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

    /**
     * Sends a reply's packets: all in one write, so a client's short end wait (#427) never runs out between two
     * packets, or with {@code pieces} as {@link #sendInPieces} says.
     */
    private void send(PushbackInputStream in, OutputStream out, List<byte[]> packets) throws IOException {
        if (quirks.pieces()) {
            sendInPieces(in, out, packets);
            return;
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] packet : packets) {
            all.write(packet);
        }
        all.writeTo(out);
        out.flush();
    }

    /**
     * Sends each packet in four pieces, each written and flushed on its own: two bytes of the length, the rest of the
     * header, then the body in two halves. So a client sees a split inside the length, inside the body, and (for a
     * multi-packet reply) between packets.
     *
     * <p>Which pauses are safe. The client reads the first packet with its full timeout, so the pieces of the first
     * packet are each followed by a short pause: the client reads before the rest has arrived. After the first packet
     * of a long reply the client sends its end marker and waits only its end wait (200 ms in the #427 test) for each
     * further byte; a timed pause there could run it out on a loaded machine and cut the reply. So between the first
     * and the second packet the server waits for an event instead: the end marker's first byte, which proves the
     * client has read the whole first packet and nothing of the second. The remaining pieces then follow at once,
     * still flushed one by one. Even that is a round trip on the end wait, so a test using pieces with a reply over
     * one packet gives the client a generous end wait. The answer to the end marker itself goes out whole.
     */
    private static void sendInPieces(PushbackInputStream in, OutputStream out, List<byte[]> packets)
            throws IOException {
        for (int p = 0; p < packets.size(); p++) {
            byte[] packet = packets.get(p);
            if (p == 1) {
                int next = in.read(); // the end marker's first byte; it stays for the next request read
                if (next < 0) {
                    return;
                }
                in.unread(next);
            }
            int[] cuts = {0, 2, 12, 12 + (packet.length - 12) / 2, packet.length};
            for (int c = 0; c + 1 < cuts.length; c++) {
                out.write(packet, cuts[c], cuts[c + 1] - cuts[c]);
                out.flush();
                if (p == 0 && c + 2 < cuts.length) {
                    pause();
                }
            }
        }
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(PIECE_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted between two pieces");
        }
    }

    private static byte[] packet(int id, int type, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(14 + bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(10 + bytes.length).putInt(id).putInt(type).put(bytes).put((byte) 0).put((byte) 0);
        return buffer.array();
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
