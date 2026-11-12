package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RconTest {
    @TempDir
    Path dir;

    @Test
    void targetComesFromServerPropertiesOnlyWhenRconIsOn() throws Exception {
        Files.writeString(dir.resolve("server.properties"), "enable-rcon=true\nrcon.port=25599\nrcon.password=s3cret\n");
        assertThat(Rcon.target(dir)).isEqualTo(new Rcon.Target("127.0.0.1", 25599, "s3cret"));
        Files.writeString(dir.resolve("server.properties"), "enable-rcon=false\nrcon.password=s3cret\n");
        assertThat(Rcon.target(dir)).isNull();
        assertThat(Rcon.target(dir.resolve("missing"))).isNull();
    }

    @Test
    void sendsTheCommandAfterLoggingIn() throws Exception {
        List<String> received = new ArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread fake = Thread.ofVirtual().start(() -> serve(server, "pw", received));
            assertThat(Rcon.send(new Rcon.Target("127.0.0.1", server.getLocalPort(), "pw"), "stop")).isTrue();
            fake.join(5000);
        }
        assertThat(received).containsExactly("pw", "stop");
    }

    @Test
    void aWrongPasswordIsRefused() throws Exception {
        List<String> received = new ArrayList<>();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread fake = Thread.ofVirtual().start(() -> serve(server, "pw", received));
            assertThat(Rcon.send(new Rcon.Target("127.0.0.1", server.getLocalPort(), "nope"), "stop")).isFalse();
            fake.join(5000);
        }
        assertThat(received).containsExactly("nope");
    }

    /** A vanilla-like RCON server: answers the login with the request id (or -1), then echoes command ids. */
    private static void serve(ServerSocket server, String password, List<String> received) {
        try (Socket s = server.accept()) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            while (true) {
                byte[] head = new byte[4];
                in.readFully(head);
                byte[] rest = new byte[ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt()];
                in.readFully(rest);
                ByteBuffer b = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
                int id = b.getInt();
                int type = b.getInt();
                String body = new String(rest, 8, rest.length - 10, StandardCharsets.US_ASCII);
                synchronized (received) {
                    received.add(body);
                }
                boolean ok = type != 3 || body.equals(password);
                out.write(Rcon.packet(ok ? id : -1, 2, ""));
                out.flush();
                if (!ok || type == 2) {
                    return;
                }
            }
        } catch (Exception e) {
            // Client closed.
        }
    }
}
