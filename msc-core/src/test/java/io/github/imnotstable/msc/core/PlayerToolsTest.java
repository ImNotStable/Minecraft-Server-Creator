package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

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
    void saysSoWhenTheServerIsAnotherMinecraftVersion() throws Exception {
        try (CoreHarness h = new CoreHarness(workspace).withAgent()) {
            h.agent.minecraft = "26.2";
            assertThat(h.error("player_test", "actions", List.of("jump")))
                    .contains("Minecraft 26.2", "speaks " + io.github.imnotstable.msc.bot.TestPlayer.MINECRAFT_VERSION);
        }
    }
}
