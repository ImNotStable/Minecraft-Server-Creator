package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.BedrockConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0046: {@code server_setup bedrock=true} puts Geyser-Spigot and Floodgate in plugins/ with a Geyser config, a
 * rerun updates the jars and keeps the config, {@code bedrock=false} takes the jars out, and a platform without
 * plugins is refused before anything changes. Fake GeyserMC API; no real download, the EULA is never accepted.
 */
class BedrockSetupTest {
    private static FakeDownloads downloads;

    @TempDir
    Path dir;

    @BeforeAll
    static void serve() throws Exception {
        downloads = FakeDownloads.start();
    }

    @AfterAll
    static void stop() {
        downloads.close();
    }

    @Test
    void bedrockTrueInstallsBothPluginsAndAConfigThatARerunKeeps() throws Exception {
        Path ws = Files.createDirectories(dir.resolve("ws"));
        Path plugins = ws.resolve("run/plugins");
        try (CoreHarness h = new CoreHarness(ws, downloads::apply)) {
            String first = h.ok("server_setup", "platform", "paper", "bedrock", true);

            assertThat(first).contains("geyser 2.12.1 build 1302 (downloaded), floodgate 2.2.5 build 141 (downloaded)\n")
                    .containsPattern("\nbedrock: UDP 0\\.0\\.0\\.0:\\d+ \\(Geyser, fronting Java port \\d+; "
                            + "Floodgate sign-in, open to the network\\)\n");
            assertThat(plugins.resolve("Geyser-Spigot.jar")).hasContent("geyser jar");
            assertThat(plugins.resolve("floodgate-spigot.jar")).hasContent("floodgate jar");
            Path config = BedrockConfig.file(ws.resolve("run"));
            assertThat(Files.readString(config)).contains("auth-type: floodgate");

            // The user's edit stays; a plain rerun (no bedrock argument) keeps Geyser and finds the jars in place.
            String edited = Files.readString(config).replace("auth-type: floodgate", "auth-type: online");
            Files.writeString(config, edited);
            String again = h.ok("server_setup");

            assertThat(again).contains("geyser 2.12.1 build 1302 (already there), floodgate 2.2.5 build 141 "
                    + "(already there)");
            assertThat(config).hasContent(edited);

            // A newer build replaces the older jar under the same name.
            Files.writeString(plugins.resolve("Geyser-Spigot.jar"), "older build");
            assertThat(h.ok("server_setup", "bedrock", true)).contains("geyser 2.12.1 build 1302 (cached)");
            assertThat(plugins.resolve("Geyser-Spigot.jar")).hasContent("geyser jar");

            String off = h.ok("server_setup", "bedrock", false);

            assertThat(off).contains("removed Geyser and Floodgate from plugins/ (their config folders stay)")
                    .doesNotContain("bedrock: UDP");
            assertThat(plugins.resolve("Geyser-Spigot.jar")).doesNotExist();
            assertThat(plugins.resolve("floodgate-spigot.jar")).doesNotExist();
            assertThat(config).hasContent(edited);
            assertThat(h.ok("server_setup")).doesNotContain("geyser").doesNotContain("removed Geyser");
        }
    }

    @Test
    void purpurTakesTheSamePlugins() throws Exception {
        Path ws = Files.createDirectories(dir.resolve("purpur"));
        try (CoreHarness h = new CoreHarness(ws, downloads::apply)) {
            assertThat(h.ok("server_setup", "platform", "purpur", "bedrock", true)).contains("bedrock: UDP ");
            assertThat(ws.resolve("run/plugins/Geyser-Spigot.jar")).exists();
        }
    }

    @Test
    void bedrockAndVelocityForwardingAreRefusedTogetherBeforeAnythingChanges() throws Exception {
        Path ws = Files.createDirectories(dir.resolve("proxied"));
        Path plugins = ws.resolve("run/plugins");
        try (CoreHarness h = new CoreHarness(ws, c -> downloads.apply(c).withProxySecret(dir.resolve("velocity.secret")))) {
            Map<String, String> before = TreeSnapshot.of(ws);
            assertThat(h.error("server_setup", "platform", "paper", "bedrock", true, "proxy", "velocity"))
                    .isEqualTo("error: bedrock=true does not work behind a proxy (the backend refuses direct "
                            + "joins, Geyser's too)");
            assertThat(TreeSnapshot.of(ws)).isEqualTo(before);

            // Geyser first, then forwarding: refused until Geyser goes, in the same call or before.
            h.ok("server_setup", "platform", "paper", "bedrock", true);
            Map<String, String> withGeyser = TreeSnapshot.of(ws);
            assertThat(h.error("server_setup", "proxy", "velocity")).isEqualTo("error: Geyser is in plugins/ and does "
                    + "not work behind a proxy (the backend refuses direct joins, Geyser's too): bedrock=false "
                    + "removes it");
            assertThat(TreeSnapshot.of(ws)).isEqualTo(withGeyser);
            assertThat(h.ok("server_setup", "proxy", "velocity", "bedrock", false))
                    .contains("removed Geyser and Floodgate from plugins/").doesNotContain("bedrock: UDP");
            assertThat(plugins.resolve("Geyser-Spigot.jar")).doesNotExist();

            // Forwarding on: bedrock=true alone is refused too, since the folder keeps forwarding.
            assertThat(h.error("server_setup", "bedrock", true)).contains("does not work behind a proxy");
            assertThat(h.ok("server_setup", "proxy", "none", "bedrock", true)).contains("bedrock: UDP");
        }
    }

    /** A Paper folder with Geyser switched to each other platform: refused alone, cleaned with bedrock=false. */
    @Test
    void aPlatformSwitchRemovesGeyserOnlyWhenAskedAndNeverKeepsItSilently() throws Exception {
        for (String platform : new String[] {"folia", "velocity", "fabric", "minestom"}) {
            Path ws = Files.createDirectories(dir.resolve("switch-" + platform));
            Path plugins = ws.resolve("run/plugins");
            try (CoreHarness h = new CoreHarness(ws,
                    c -> downloads.apply(c).withProxySecret(dir.resolve("velocity.secret")))) {
                h.ok("server_setup", "platform", "paper", "bedrock", true);
                Map<String, String> before = TreeSnapshot.of(ws);

                assertThat(h.error("server_setup", "platform", platform)).as(platform).isEqualTo(platform.equals("velocity")
                        ? "error: Geyser in plugins/ is the spigot build, which velocity does not load: bedrock=true "
                        + "installs velocity's, bedrock=false removes it" : "error: Geyser is in plugins/ and needs paper, "
                        + "purpur, velocity or waterfall (Geyser and Floodgate install as plugins), not " + platform
                        + ": bedrock=false removes it");
                assertThat(TreeSnapshot.of(ws)).as(platform).isEqualTo(before);

                assertThat(h.ok("server_setup", "platform", platform, "bedrock", false)).as(platform)
                        .contains("removed Geyser and Floodgate from plugins/ (their config folders stay)");
                assertThat(plugins.resolve("Geyser-Spigot.jar")).as(platform).doesNotExist();
                assertThat(plugins.resolve("floodgate-spigot.jar")).as(platform).doesNotExist();
                assertThat(BedrockConfig.file(ws.resolve("run"))).as(platform).exists();
                assertThat(h.ok("server_setup")).as(platform).doesNotContain("Geyser");
            }
        }
    }

    @Test
    void aPlatformWithoutPluginsIsRefusedBeforeAnythingChanges() throws Exception {
        for (String platform : new String[] {"fabric", "quilt", "neoforge", "forge", "minestom"}) {
            Path ws = Files.createDirectories(dir.resolve(platform));
            try (CoreHarness h = new CoreHarness(ws, downloads::apply)) {
                Map<String, String> before = TreeSnapshot.of(ws);

                assertThat(h.error("server_setup", "platform", platform, "bedrock", true)).isEqualTo(
                        "error: bedrock=true needs paper, purpur, velocity or waterfall (Geyser and Floodgate install as plugins), not "
                                + platform);
                assertThat(TreeSnapshot.of(ws)).isEqualTo(before);
            }
        }
    }
}
