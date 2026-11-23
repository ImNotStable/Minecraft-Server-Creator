package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.server.Network;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0088 end to end against local fakes: Waterfall and its BungeeCord backends, a proxy's network of Paper backends,
 * Geyser on a proxy, and the refusals that come before anything changes. No server runs.
 */
class ProxyNetworkTest {
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
        Path home = dir.resolve("home");
        return new CoreHarness(Files.createDirectories(dir.resolve(name)), c -> downloads.apply(c)
                .withProxySecret(home.resolve(".msc/velocity.secret")).withMachineEula(MachineEula.forHome(home)));
    }

    @Test
    void aBackendForwardsFromWaterfallThroughSpigotYml() throws Exception {
        try (CoreHarness backend = harness("backend")) {
            assertThat(backend.ok("server_setup", "platform", "paper", "proxy", "bungeecord"))
                    .contains("players join through the Waterfall proxy");
            assertThat(dir.resolve("backend/run/spigot.yml")).content().contains("settings:\n  bungeecord: true\n");
            // Switching to Velocity turns BungeeCord forwarding off, and back.
            backend.ok("server_setup", "proxy", "velocity");
            assertThat(dir.resolve("backend/run/spigot.yml")).content().contains("  bungeecord: false\n");
            assertThat(dir.resolve("backend/run/config/paper-global.yml")).content().contains("    enabled: true\n");
            backend.ok("server_setup", "proxy", "none");
            assertThat(dir.resolve("backend/run/config/paper-global.yml")).content().contains("    enabled: false\n");
        }
    }

    @Test
    void aNetworkNeedsTheEulaBeforeAnythingChanges() throws Exception {
        try (CoreHarness proxy = harness("noeula")) {
            Map<String, String> before = TreeSnapshot.of(dir.resolve("noeula"));
            assertThat(proxy.error("server_setup", "platform", "velocity", "servers", "lobby"))
                    .startsWith("error: the network's backends run Mojang's server");
            assertThat(TreeSnapshot.of(dir.resolve("noeula"))).isEqualTo(before);
            assertThat(proxy.error("server_setup", "platform", "paper", "servers", "lobby"))
                    .isEqualTo("error: servers is for a proxy (velocity or waterfall): its backends; set up a backend "
                            + "with proxy= instead");
        }
    }

    @Test
    void velocityGetsABackendPerNameAndListsThem() throws Exception {
        try (CoreHarness proxy = harness("velocity")) {
            String out = proxy.ok("server_setup", "platform", "velocity", "servers", "lobby\ngame", "acceptEula", true);
            assertThat(out).contains("paper " + FakeDownloads.MINECRAFT + " for 2 backends")
                    .contains("network: lobby on 127.0.0.1:25566 (players land here), game on 127.0.0.1:25567")
                    .doesNotContain("backend: a paper");
            Path run = dir.resolve("velocity/run");
            assertThat(run.resolve("velocity.toml")).content()
                    .contains("[servers]\nlobby = \"127.0.0.1:25566\"\ngame = \"127.0.0.1:25567\"\ntry = [\n    \"lobby\"\n]")
                    .doesNotContain("127.0.0.1:25565");
            assertThat(run.resolve(Network.FILE)).hasContent("lobby 25566\ngame 25567");
            for (String name : new String[] {"lobby", "game"}) {
                Path b = run.resolve("servers").resolve(name);
                assertThat(b.resolve("paper.jar")).hasContent("paper server");
                assertThat(b.resolve("eula.txt")).content().contains("eula=true");
                assertThat(b.resolve("config/paper-global.yml")).content().contains("    enabled: true\n");
                assertThat(b.resolve("plugins")).isDirectory();
            }
            assertThat(ServerPropertiesFile.read(run.resolve("servers/game/server.properties")))
                    .containsEntry("server-port", "25567").containsEntry("server-ip", "127.0.0.1")
                    .containsEntry("online-mode", "false");
            // No backend runs yet: a command for one says how to start them.
            assertThat(proxy.error("server_command", "command", "@lobby list"))
                    .isEqualTo("error: backend lobby is not running; server_start starts the network");
        }
    }

    @Test
    void waterfallGetsItsModulesAndANetworkInConfigYml() throws Exception {
        try (CoreHarness proxy = harness("waterfall")) {
            proxy.ok("server_setup", "platform", "waterfall", "servers", "hub", "version", FakeDownloads.MINECRAFT,
                    "acceptEula", true);
            Path run = dir.resolve("waterfall/run");
            assertThat(run.resolve("modules/cmd_server.jar")).hasContent("cmd_server module");
            assertThat(run.resolve("config.yml")).content().contains("servers:\n  hub:\n    address: 127.0.0.1:25566\n")
                    .contains("  priorities:\n  - hub\n").doesNotContain("lobby");
            assertThat(run.resolve("servers/hub/spigot.yml")).content().contains("  bungeecord: true\n");
        }
    }

    @Test
    void geyserOnAProxyIsItsOwnBuildFrontingTheProxyPort() throws Exception {
        try (CoreHarness proxy = harness("geyser")) {
            String out = proxy.ok("server_setup", "platform", "velocity", "bedrock", true);
            Path plugins = dir.resolve("geyser/run/plugins");
            assertThat(plugins.resolve("Geyser-Velocity.jar")).hasContent("geyser jar");
            assertThat(plugins.resolve("floodgate-velocity.jar")).hasContent("floodgate jar");
            assertThat(plugins.resolve("Geyser-Spigot.jar")).doesNotExist();
            assertThat(plugins.resolve("Geyser-Velocity/config.yml")).content().contains("auth-type: floodgate");
            assertThat(out).containsPattern("bedrock: UDP 0\\.0\\.0\\.0:\\d+ \\(Geyser, fronting Java port 25577;");
            // To Waterfall: the Velocity build is loaded by neither, so it is refused until replaced or removed.
            assertThat(proxy.error("server_setup", "platform", "waterfall")).isEqualTo("error: Geyser in plugins/ is "
                    + "the velocity build, which waterfall does not load: bedrock=true installs waterfall's, "
                    + "bedrock=false removes it");
            proxy.ok("server_setup", "platform", "waterfall", "bedrock", true);
            assertThat(plugins.resolve("Geyser-BungeeCord.jar")).hasContent("geyser jar");
            assertThat(plugins.resolve("floodgate-bungee.jar")).hasContent("floodgate jar");
            assertThat(plugins.resolve("Geyser-Velocity.jar")).doesNotExist();
        }
    }

    @Test
    void theTestPlayerJoinsAProxyOnlyWithANetworkAndDoesNotWalkThere() throws Exception {
        try (CoreHarness proxy = harness("player")) {
            proxy.ok("server_setup", "platform", "velocity");
            assertThat(proxy.error("player_test", "actions", "say hi")).startsWith("error: Velocity is a proxy");
            proxy.ok("server_setup", "servers", "lobby", "acceptEula", true);
            assertThat(proxy.error("player_test", "actions", "walk 1,2,3")).isEqualTo("error: \"walk 1,2,3\": "
                    + "through the proxy the test player sees no blocks (the proxy has no agent), so it does not walk "
                    + "or jump; tp, /commands, use, interact, click, say, hud and inventory work");
            assertThat(proxy.error("player_test", "actions", "say hi", "gamemode", "creative"))
                    .startsWith("error: gamemode is set on a backend behind the proxy");
        }
    }
}
