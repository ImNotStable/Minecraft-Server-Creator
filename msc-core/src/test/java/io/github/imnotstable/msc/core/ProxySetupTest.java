package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0045 end to end against local fakes: a Velocity proxy workspace and a Paper backend workspace set up apart share
 * the machine's forwarding secret, {@code proxy=} is refused where it cannot apply, world and player tools on a proxy
 * say so in one line, and Folia's setup says what is not supported there. No download, no server, no EULA.
 */
class ProxySetupTest {
    private static FakeDownloads downloads;

    @TempDir
    Path dir;

    @BeforeAll
    static void serve() throws IOException {
        downloads = FakeDownloads.start();
    }

    @AfterAll
    static void stop() {
        downloads.close();
    }

    private CoreHarness harness(String name) throws Exception {
        Path secret = dir.resolve("home/.msc/velocity.secret");
        return new CoreHarness(Files.createDirectories(dir.resolve(name)),
                c -> downloads.apply(c).withProxySecret(secret));
    }

    @Test
    void aProxyAndABackendSetUpApartShareTheSecret() throws Exception {
        try (CoreHarness proxy = harness("proxy")) {
            assertThat(proxy.ok("server_setup", "platform", "velocity"))
                    .contains("players join 127.0.0.1:25577, sent to the backend at 127.0.0.1:25565");
        }
        try (CoreHarness backend = harness("backend")) {
            assertThat(backend.ok("server_setup", "platform", "paper", "proxy", "velocity"))
                    .contains("players join only through the Velocity proxy");
        }
        String secret = Files.readString(dir.resolve("proxy/run/forwarding.secret"));
        assertThat(secret).isEqualTo(Files.readString(dir.resolve("home/.msc/velocity.secret")).strip());
        assertThat(dir.resolve("backend/run/config/paper-global.yml")).content()
                .contains("    enabled: true\n", "    online-mode: false\n", "    secret: '" + secret + "'\n");

        try (CoreHarness backend = harness("backend")) {
            assertThat(backend.ok("server_setup", "proxy", "none")).contains("players join directly");
        }
        assertThat(dir.resolve("backend/run/config/paper-global.yml")).content().contains("    enabled: false\n");
    }

    @Test
    void proxyIsRefusedWhereItCannotApplyAndChangesNothing() throws Exception {
        try (CoreHarness s = harness("ws")) {
            assertThat(s.error("server_setup", "platform", "fabric", "proxy", "velocity"))
                    .contains("proxy= sets up a paper, purpur or folia backend, not fabric");
            assertThat(s.error("server_setup", "platform", "velocity", "proxy", "velocity"))
                    .contains("the proxy itself always forwards");
            assertThat(s.error("server_setup", "proxy", "spigot")).contains("proxy must be velocity, bungeecord or none");
        }
        assertThat(dir.resolve("ws/run")).doesNotExist();
        assertThat(dir.resolve("home/.msc/velocity.secret")).doesNotExist();
    }

    @Test
    void worldAndPlayerToolsOnAProxySaySoInOneLine() throws Exception {
        try (CoreHarness s = harness("proxy")) {
            s.ok("server_setup", "platform", "velocity");
            String line = Platform.VELOCITY.noWorldTools();
            assertThat(s.error("world_summary", "region", "0,0,0 1,1,1")).contains(line).doesNotContain("\n");
            assertThat(s.error("build_batch", "steps", "set 0,0,0 stone")).contains(line);
            assertThat(s.error("player_test", "actions", java.util.List.of("say hi"))).contains(line);
        }
    }

    @Test
    void foliaSetupSaysWhatItNeedsAndWhatDoesNotWork() throws Exception {
        try (CoreHarness s = harness("folia")) {
            assertThat(s.ok("server_setup", "platform", "folia"))
                    .contains("folia " + FakeDownloads.MINECRAFT + " build 3 (downloaded)")
                    .contains("folia-supported: true").contains("world, build and player tools do not work on Folia");
        }
        assertThat(dir.resolve("folia/run/folia.jar")).hasContent("folia server");
    }
}
