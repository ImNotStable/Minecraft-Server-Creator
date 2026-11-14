package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.FakeLeftover;
import io.github.imnotstable.msc.process.ClosedPort;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import io.github.imnotstable.msc.process.rcon.FakeRconServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** server_stop asks a leftover server over RCON where player_test joins it: the same reading of server-ip (#448). */
class LeftoverRconAddressTest {
    @TempDir
    Path workspace;

    @Test
    void aWildcardServerIpIsAskedOnLoopback() throws Exception {
        try (CoreHarness h = new CoreHarness(workspace)) {
            AtomicReference<FakeLeftover> leftover = new AtomicReference<>();
            try (FakeRconServer rcon = new FakeRconServer("pw", cmd -> {
                leftover.get().exit(); // stands in for the server saving and exiting
                return "Stopping the server";
            })) {
                // Generous: the leftover exits as soon as "stop" arrives, so the wait is never waited out.
                leftover.set(FakeLeftover.install(h.core.server(), Duration.ofSeconds(60)));
                properties(h, "server-ip=0.0.0.0\nenable-rcon=true\nrcon.password=pw\nrcon.port=" + rcon.port());
                assertThat(h.ok("server_stop")).contains("pid " + leftover.get().pid(), "saved over RCON")
                        .doesNotContain("\n");
                assertThat(rcon.commands()).containsExactly("stop");
            }
        }
    }

    @Test
    void aPortThatIsNotANumberIsTheServersDefault() throws Exception {
        // The server reads rcon.port=abc as 25575; held closed here, so the ask is refused and the kill follows.
        try (CoreHarness h = new CoreHarness(workspace);
             ClosedPort _ = CoreHarness.holdDefaultPort(25575)) {
            FakeLeftover leftover = FakeLeftover.install(h.core.server(), Duration.ofMillis(1));
            leftover.diesWhenKilled();
            properties(h, "server-ip=0.0.0.0\nenable-rcon=true\nrcon.password=pw\nrcon.port=abc");
            assertThat(h.ok("server_stop")).contains("pid " + leftover.pid(), "killed").doesNotContain("\n");
            assertThat(leftover.destroys.get()).as("asked to end after RCON was refused").isEqualTo(1);
        }
    }

    private static void properties(CoreHarness h, String text) throws Exception {
        Path dir = Files.createDirectories(h.core.config().serverDir());
        Files.writeString(dir.resolve(ServerPropertiesFile.NAME), text + "\n");
    }
}
