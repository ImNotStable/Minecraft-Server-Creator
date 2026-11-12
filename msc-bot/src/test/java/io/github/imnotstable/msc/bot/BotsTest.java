package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BotsTest {
    private static final Path PROTOCOLS = Path.of(System.getProperty("msc.bot.protocols"));

    @Test
    void speaksTheBuiltInAndThePreviousProtocol() {
        Map<Integer, Bots.Speaker> speakers = Bots.speakers(PROTOCOLS);
        assertThat(speakers.keySet()).containsExactly(777, 776);
        Bots.Speaker previous = speakers.get(776);
        // A fresh copy of the bot classes, linked against MCProtocolLib 26.2, behind the shared Bot interface.
        assertThat(previous.minecraft()).isEqualTo("26.2");
        assertThat(previous.loader()).isNotSameAs(Bots.class.getClassLoader());
        assertThat(Bots.speakers(null).keySet()).containsExactly(777);
    }

    @Test
    void namesTheVersionsWhenTheServerRunsAnotherOne() throws Exception {
        try (ServerSocket server = statusServer("{\"version\":{\"name\":\"26.1\",\"protocol\":775}}")) {
            assertThatThrownBy(() -> Bots.join("MscTester", "127.0.0.1", server.getLocalPort(), r -> null,
                    Duration.ofSeconds(2), PROTOCOLS))
                    .hasMessage("the server runs 26.1 (protocol 775); the test player speaks 26.3 (777), 26.2 (776)");
        }
    }

    @Test
    void pingReadsTheServerVersion() throws Exception {
        try (ServerSocket server = statusServer("{\"version\":{\"name\":\"Minestom 26.2\",\"protocol\":776}}")) {
            assertThat(ServerPing.version("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2)))
                    .isEqualTo(new ServerPing.Version(776, "Minestom 26.2"));
        }
    }

    @Test
    void varIntsRoundTrip() throws IOException {
        for (int v : new int[] {0, 1, 127, 128, 25565, -1, Integer.MAX_VALUE}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ServerPing.writeVarInt(out, v);
            assertThat(ServerPing.readVarInt(new ByteArrayInputStream(out.toByteArray()))).isEqualTo(v);
        }
    }

    /** A server that answers one status request with {@code json}. */
    private static ServerSocket statusServer(String json) throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            try (Socket s = server.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                in.readFully(new byte[ServerPing.readVarInt(in)]); // handshake
                in.readFully(new byte[ServerPing.readVarInt(in)]); // status request
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream packet = new ByteArrayOutputStream();
                ServerPing.writeVarInt(packet, 0);
                ServerPing.writeVarInt(packet, body.length);
                packet.write(body);
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                ServerPing.writeVarInt(frame, packet.size());
                packet.writeTo(frame);
                s.getOutputStream().write(frame.toByteArray());
            } catch (IOException e) {
                // the test fails on its own assertion
            }
        });
        return server;
    }
}
