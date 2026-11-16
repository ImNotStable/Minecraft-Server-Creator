package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.process.rcon.FakeRconServer;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hang fails
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
    @ValueSource(strings = {"pässwörd", "密码", "pw€", "a\\b", " lead", "#x=y:z"})
    void logsInWithThePasswordTheServerReads(String password) throws Exception {
        // #380, #391: the 26.3 server stores server.properties with Properties.store over a UTF-8 writer, so a
        // non-ASCII password is raw UTF-8 on disk. Older files and Properties.store(OutputStream) hold it \\u-escaped
        // in ISO-8859-1. Both must give the password the server checks logins against.
        Properties p = new Properties();
        p.setProperty("enable-rcon", "true");
        p.setProperty("rcon.password", password);
        Writer utf8 = new StringWriter();
        p.store(utf8, "Minecraft server properties");
        ByteArrayOutputStream escaped = new ByteArrayOutputStream();
        p.store(escaped, "Minecraft server properties");
        for (byte[] file : List.of(utf8.toString().getBytes(StandardCharsets.UTF_8), escaped.toByteArray())) {
            Files.write(dir.resolve("server.properties"), file);
            assertThat(RconTarget.of(dir).password()).isEqualTo(password);
            try (FakeRconServer server = new FakeRconServer(password, cmd -> "Stopping the server")) {
                assertThat(new RconTarget("127.0.0.1", server.port(), password).askToStop()).isTrue();
                assertThat(server.commands()).containsExactly("stop");
            }
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // #394: a port the server refuses ("rcon disabled!") is no target, so nothing can throw on connect.
            "enable-rcon=true;rcon.password=pw;rcon.port=70000|",
            "enable-rcon=true;rcon.password=pw;rcon.port=-1|",
            "enable-rcon=true;rcon.password=pw;rcon.port=0|",
            "enable-rcon=true;rcon.password=pw;rcon.port=65536|",
            "enable-rcon=true;rcon.password=;rcon.port=25575|",
            "enable-rcon=yes;rcon.password=pw|",
            "enable-rcon=true ;rcon.password=pw|",
            "rcon.password=pw|",
            "enable-rcon=true;rcon.password=\\uZZZZ|",
            // A number the server cannot parse is its default; enable-rcon is read in any case (#396).
            "enable-rcon=true;rcon.password=pw;rcon.port=abc|127.0.0.1:25575",
            "rcon.port=25599 ;enable-rcon=true;rcon.password=pw|127.0.0.1:25575",
            "enable-rcon=TRUE;rcon.password=pw;rcon.port=65535|127.0.0.1:65535",
            "enable-rcon=True;rcon.password=pw;rcon.port=1|127.0.0.1:1",
            // The server binds RCON to server-ip (#396).
            "enable-rcon=true;rcon.password=pw;server-ip=192.168.1.20|192.168.1.20:25575",
            "enable-rcon=true;rcon.password=pw;server-ip=0.0.0.0|127.0.0.1:25575",
            "enable-rcon=true;rcon.password=pw;server-ip=::|::1:25575",
            "enable-rcon=true;rcon.password=pw;server-ip=|127.0.0.1:25575"})
    void targetFollowsTheServersOwnRules(String text, String expected) throws Exception {
        Files.writeString(dir.resolve("server.properties"), text.replace(";", "\n"));
        RconTarget target = RconTarget.of(dir);
        assertThat(target == null ? null : target.host() + ":" + target.port()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65536, 70000, Integer.MAX_VALUE})
    void askingAnUnreachablePortNeverThrows(int port) {
        // #394: an IllegalArgumentException from the socket address escaped and skipped the kill fallback.
        assertThat(new RconTarget("127.0.0.1", port, "pw").askToStop()).isFalse();
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
