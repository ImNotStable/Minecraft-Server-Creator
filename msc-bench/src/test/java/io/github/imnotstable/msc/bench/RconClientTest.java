package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.bench.rcon.RconClient;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RconClientTest {

    @Test
    void logsInAndRunsCommands() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret", cmd -> "ran " + cmd);
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "secret", Duration.ofSeconds(5))) {
            assertThat(client.command("list")).isEqualTo("ran list");
            assertThat(client.command("say é")).isEqualTo("ran say é");
            assertThat(server.commands()).containsExactly("list", "say é");
        }
    }

    @Test
    void refusesAWrongPassword() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret", cmd -> "")) {
            assertThatThrownBy(() -> RconClient.connect("127.0.0.1", server.port(), "nope", Duration.ofSeconds(5)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("refused");
        }
    }
}
