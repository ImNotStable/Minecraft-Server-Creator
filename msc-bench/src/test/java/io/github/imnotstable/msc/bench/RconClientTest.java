package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.bench.rcon.RconClient;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(ints = {FakeRconServer.CHUNK - 1, FakeRconServer.CHUNK, FakeRconServer.CHUNK + 1, 3 * FakeRconServer.CHUNK + 7})
    void readsAReplySplitOverSeveralPackets(int length) throws IOException {
        // Only the first 4096 characters came back: a long "data get" or block list was cut and checks misread it.
        String longReply = "x".repeat(length - 1) + "!";
        try (FakeRconServer server = new FakeRconServer("secret", cmd -> cmd.equals("long") ? longReply : "short");
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "secret", Duration.ofSeconds(30))) {
            assertThat(client.command("long")).isEqualTo(longReply);
            assertThat(client.command("next")).as("no packet of the long reply is left for the next command")
                    .isEqualTo("short");
            assertThat(server.commands()).containsExactly("long", "next");
        }
    }

    @Test
    void readsAnEmptyReply() throws IOException {
        try (FakeRconServer server = new FakeRconServer("secret", cmd -> "");
                RconClient client = RconClient.connect("127.0.0.1", server.port(), "secret", Duration.ofSeconds(30))) {
            assertThat(client.command("say hi")).isEmpty();
            assertThat(client.command("say again")).isEmpty();
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
