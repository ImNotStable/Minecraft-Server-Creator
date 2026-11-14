package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.process.rcon.FakeRconServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RconTargetTest {
    @TempDir
    Path dir;

    @Test
    void targetComesFromServerPropertiesOnlyWhenRconIsOn() throws Exception {
        Files.writeString(dir.resolve("server.properties"), "enable-rcon=true\nrcon.port=25599\nrcon.password=s3cret\n");
        assertThat(RconTarget.of(dir)).isEqualTo(new RconTarget("127.0.0.1", 25599, "s3cret"));
        Files.writeString(dir.resolve("server.properties"), "enable-rcon=false\nrcon.password=s3cret\n");
        assertThat(RconTarget.of(dir)).isNull();
        assertThat(RconTarget.of(dir.resolve("missing"))).isNull();
    }

    @Test
    void aMalformedEscapeIsNoTarget() throws Exception {
        Files.writeString(dir.resolve("server.properties"), "enable-rcon=true\nrcon.password=\\uZZZZ\n");
        assertThat(RconTarget.of(dir)).isNull();
    }

    @Test
    void sendsStopAfterLoggingIn() throws Exception {
        try (FakeRconServer server = new FakeRconServer("pw", cmd -> "Stopping the server")) {
            assertThat(new RconTarget("127.0.0.1", server.port(), "pw").askToStop()).isTrue();
            assertThat(server.commands()).containsExactly("stop");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"p\\u00e4ssw\\u00f6rd", "\\u5bc6\\u7801", "pw\\u20ac"})
    void logsInWithANonAsciiPasswordFromServerProperties(String escaped) throws Exception {
        // #380: the password was sent as US-ASCII, so a non-ASCII one was refused and the leftover server was killed
        // instead of stopped and saved. server.properties holds it \\u-escaped, as vanilla writes it.
        Files.writeString(dir.resolve("server.properties"), "enable-rcon=true\nrcon.password=" + escaped + "\n",
                StandardCharsets.ISO_8859_1);
        String password = RconTarget.of(dir).password();
        assertThat(password).doesNotContain("\\");
        assertThat(password.chars()).as("decoded to non-ASCII").anyMatch(c -> c > 127);
        try (FakeRconServer server = new FakeRconServer(password, cmd -> "Stopping the server")) {
            assertThat(new RconTarget("127.0.0.1", server.port(), password).askToStop()).isTrue();
            assertThat(server.commands()).containsExactly("stop");
        }
    }

    @Test
    void aWrongPasswordIsRefused() throws Exception {
        try (FakeRconServer server = new FakeRconServer("pw", cmd -> "Stopping the server")) {
            assertThat(new RconTarget("127.0.0.1", server.port(), "nope").askToStop()).isFalse();
            assertThat(server.commands()).isEmpty();
        }
    }

    @Test
    void aServerThatIsGoneIsNotAsked() throws Exception {
        int port;
        try (FakeRconServer server = new FakeRconServer("pw", cmd -> "")) {
            port = server.port();
        }
        // The port is free again, so nothing answers on it (unless another process took it, which also refuses "pw").
        assertThat(new RconTarget("127.0.0.1", port, "pw").askToStop()).isFalse();
    }
}
