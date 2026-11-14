package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.process.ClosedPort;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlayerToolsTest {
    @TempDir
    Path workspace;

    @Test
    void needsARunningServer() throws Exception {
        try (CoreHarness h = new CoreHarness(workspace)) {
            assertThat(h.error("player_test", "actions", List.of("jump"))).contains("server_start");
        }
    }

    @Test
    void checksArgumentsBeforeJoining() throws Exception {
        try (CoreHarness h = new CoreHarness(workspace).withAgent()) {
            assertThat(h.error("player_test", "actions", List.of())).contains("actions is empty");
            assertThat(h.error("player_test", "actions", List.of("jump"), "name", "a b")).contains("3 to 16");
            assertThat(h.error("player_test", "actions", List.of("jump"), "gamemode", "spectator"))
                    .contains("survival, adventure or creative");
        }
    }

    @Test
    void leavesTheVersionCheckToTheServerPing() throws Exception {
        // A 26.2 server (Minestom) is no longer refused up front: the test player pings it and picks its client.
        try (CoreHarness h = new CoreHarness(workspace).withAgent();
             ClosedPort closed = CoreHarness.noGameServer(h.core.config().serverDir())) {
            h.agent.minecraft = "26.2";
            assertThat(h.error("player_test", "actions", List.of("jump")))
                    .contains("no Minecraft server answers at 127.0.0.1:" + closed.port());
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // #448: a wildcard server-ip was dialled as is, which Windows refuses; it means loopback, as for RCON.
            "server-ip=0.0.0.0|no Minecraft server answers at 127.0.0.1:{port}",
            "server-ip=|no Minecraft server answers at 127.0.0.1:{port}",
            // #448: a port the server cannot parse is its default 25565, not "error: internal: NumberFormatException".
            "server-port=abc|no Minecraft server answers at 127.0.0.1:25565",
            "server-port=70000|server-port=70000 in server.properties is not a port from 1 to 65535",
            "server-port=0|server-port=0 in server.properties is not a port from 1 to 65535",
            "motd=\\uZZZZ|cannot read server.properties"})
    void joinsWhereTheServerTakesPlayers(String line, String expected) throws Exception {
        try (CoreHarness h = new CoreHarness(workspace).withAgent();
             ClosedPort closed = CoreHarness.noGameServer(h.core.config().serverDir());
             ClosedPort _ = expected.contains(":25565") ? CoreHarness.holdDefaultPort(25565) : null) {
            Path props = h.core.config().serverDir().resolve(ServerPropertiesFile.NAME);
            // Appended after the held port, so a later server-port wins as it does for the server.
            Files.writeString(props, line + "\n", StandardOpenOption.APPEND);
            String error = h.error("player_test", "actions", List.of("jump"));
            assertThat(error).startsWith("error: ").doesNotContain("internal", "\n")
                    .contains(expected.replace("{port}", Integer.toString(closed.port())));
        }
    }
}
