package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

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
}
