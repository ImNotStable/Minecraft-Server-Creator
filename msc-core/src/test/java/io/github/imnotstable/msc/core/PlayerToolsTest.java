package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.process.ClosedPort;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
