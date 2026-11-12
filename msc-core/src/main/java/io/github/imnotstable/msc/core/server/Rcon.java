package io.github.imnotstable.msc.core.server;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Just enough of the Minecraft RCON protocol to send one command. Used to stop a server whose console belongs to a
 * core that is gone, so it saves the world instead of being killed.
 */
public final class Rcon {
    private static final int AUTH = 3;
    private static final int COMMAND = 2;
    private static final int TIMEOUT_MS = 3000;

    private Rcon() {
    }

    /** RCON settings from a server directory's {@code server.properties}, or null when RCON is off or unset. */
    public record Target(String host, int port, String password) {
    }

    public static Target target(Path serverDir) {
        Path file = serverDir.resolve("server.properties");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        String password = p.getProperty("rcon.password", "");
        if (!"true".equals(p.getProperty("enable-rcon", "false").strip()) || password.isBlank()) {
            return null;
        }
        try {
            return new Target("127.0.0.1", Integer.parseInt(p.getProperty("rcon.port", "25575").strip()), password);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Log in and send one command. Returns false when the server refused or could not be reached. */
    public static boolean send(Target target, String command) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(target.host(), target.port()), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            write(out, 1, AUTH, target.password());
            if (readId(in) != 1) {
                return false; // -1 means a wrong password
            }
            write(out, 2, COMMAND, command);
            try {
                readId(in);
            } catch (IOException e) {
                // "stop" may close the connection before answering; the command was still received.
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static byte[] packet(int id, int type, String body) {
        byte[] text = body.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 + text.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(4 + 4 + text.length + 2).putInt(id).putInt(type).put(text).put((byte) 0).put((byte) 0);
        return b.array();
    }

    private static void write(OutputStream out, int id, int type, String body) throws IOException {
        out.write(packet(id, type, body));
        out.flush();
    }

    /** Read one packet and return its id. */
    private static int readId(DataInputStream in) throws IOException {
        byte[] head = new byte[4];
        in.readFully(head);
        int length = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 10 || length > 4110 + 10) {
            throw new IOException("bad RCON packet length " + length);
        }
        byte[] rest = new byte[length];
        in.readFully(rest);
        return ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
}
