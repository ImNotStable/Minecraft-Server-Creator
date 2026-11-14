package io.github.imnotstable.msc.process.rcon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FakeRconServerTest {

    @Test
    void closeFreesThePortBeforeItReturns() throws IOException {
        FakeRconServer server = new FakeRconServer("pw", command -> "");
        int port = server.port();
        for (int i = 0; i < 50; i++) {
            server.close();
            server = new FakeRconServer(port, "pw", command -> "");
        }
        server.close();
        assertThat(server.port()).isEqualTo(port);
    }

    @ParameterizedTest
    @ValueSource(ints = {FakeRconServer.READ - 14, FakeRconServer.READ - 13})
    void dropsARequestThatOneReadCannotHoldLikeVanilla(int bodyBytes) throws IOException {
        // The oracle for RconClient.MAX_BODY: a body of READ - 14 bytes runs, one byte more closes the connection.
        boolean fits = bodyBytes <= FakeRconServer.READ - 14;
        try (FakeRconServer server = new FakeRconServer("pw", command -> "ok");
                Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(packet(1, 3, "pw"));
            out.flush();
            assertThat(in.readNBytes(14)).hasSize(14);
            out.write(packet(2, 2, "x".repeat(bodyBytes)));
            out.flush();
            byte[] reply = in.readNBytes(16);
            assertThat(reply).hasSize(fits ? 16 : 0);
            assertThat(server.commands()).hasSize(fits ? 1 : 0);
        }
    }

    private static byte[] packet(int id, int type, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(14 + bytes.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(10 + bytes.length).putInt(id).putInt(type).put(bytes).put((byte) 0).put((byte) 0).array();
    }
}
