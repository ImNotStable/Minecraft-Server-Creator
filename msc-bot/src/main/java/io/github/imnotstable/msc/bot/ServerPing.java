package io.github.imnotstable.msc.bot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** The server list ping: asks a server which protocol version it speaks, before joining it. */
public final class ServerPing {
    /** The protocol number and version name a server answers with, such as 776 and "26.2". */
    public record Version(int protocol, String name) {
    }

    private ServerPing() {
    }

    public static Version version(String host, int port, Duration timeout) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            OutputStream out = socket.getOutputStream();
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0); // handshake packet
            writeVarInt(handshake, -1); // protocol: unknown, as clients send for a status request
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, hostBytes.length);
            handshake.write(hostBytes);
            handshake.write(port >> 8 & 0xff);
            handshake.write(port & 0xff);
            writeVarInt(handshake, 1); // next state: status
            ByteArrayOutputStream frames = new ByteArrayOutputStream();
            writeVarInt(frames, handshake.size());
            handshake.writeTo(frames);
            writeVarInt(frames, 1); // status request: length 1, packet id 0
            writeVarInt(frames, 0);
            out.write(frames.toByteArray());
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            readVarInt(in); // frame length
            if (readVarInt(in) != 0) {
                throw new IOException("unexpected status answer");
            }
            byte[] json = new byte[readVarInt(in)];
            in.readFully(json);
            return parse(new String(json, StandardCharsets.UTF_8));
        }
    }

    static Version parse(String statusJson) throws IOException {
        try {
            JsonObject version = JsonParser.parseString(statusJson).getAsJsonObject().getAsJsonObject("version");
            return new Version(version.get("protocol").getAsInt(), version.get("name").getAsString());
        } catch (RuntimeException e) {
            throw new IOException("the server's status has no version: " + e.getMessage(), e);
        }
    }

    static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7f) != 0) {
            out.write(value & 0x7f | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("the server closed the connection");
            }
            value |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("varint too long");
    }
}
