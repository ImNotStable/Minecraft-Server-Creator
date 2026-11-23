package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0088: a proxy's backends, their list, the proxy configs that name them, and their processes. */
class NetworkTest {
    @TempDir
    Path dir;

    @Test
    void namesArePlainAndPortsFollowTheOrder() throws Exception {
        assertThat(Network.names(List.of(" Lobby", "game_2"))).containsExactly("lobby", "game_2");
        assertThatThrownBy(() -> Network.names(List.of())).hasMessageContaining("1 to " + Network.MAX);
        assertThatThrownBy(() -> Network.names(List.of("../x"))).isInstanceOf(ToolException.class);
        List<Network.Member> plan = Network.plan(dir, List.of("lobby", "game"));
        Network.write(dir, plan);
        assertThat(Network.members(dir)).isEqualTo(plan);
        assertThat(plan.get(1).address()).isEqualTo("127.0.0.1:" + (Network.FIRST_PORT + 1));
        assertThat(Network.members(dir.resolve("none"))).isEmpty();
        // Waterfall prints no "Done": its listener line is its ready line.
        assertThat(LogReader.READY.matcher("[07:46:36 INFO]: Listening on /127.0.0.1:25577").matches()).isTrue();
    }

    @Test
    void theProxyConfigsListTheNetworkInPlace() {
        SequencedMap<String, String> servers = new LinkedHashMap<>();
        servers.put("hub", "127.0.0.1:25566");
        servers.put("arena", "127.0.0.1:25567");
        String toml = ProxyForwarding.withVelocityServers(ProxyForwarding.velocityToml()
                .replace("[forced-hosts]\n", "[forced-hosts]\n\"lobby.example.com\" = [\"lobby\"]\n"), servers);
        assertThat(toml).contains("[servers]\nhub = \"127.0.0.1:25566\"\narena = \"127.0.0.1:25567\"\ntry = [\n    \"hub\"\n]")
                .doesNotContain("lobby").contains("[forced-hosts]\n\n[advanced]").contains("compression-threshold = 256");
        String yaml = ProxyForwarding.withBungeeServers(ProxyForwarding.bungeeConfig(), servers);
        assertThat(yaml).contains("  priorities:\n  - hub\n  - arena\n  force_default_server")
                .contains("servers:\n  hub:\n    address: 127.0.0.1:25566\n    motd: hub\n    restricted: false\n"
                        + "  arena:\n").contains("\npermissions:\n").doesNotContain("lobby");
        assertThat(ProxyForwarding.withNested("settings:\n  debug: false\nother: 1\n", "settings:", "bungeecord: true"))
                .isEqualTo("settings:\n  debug: false\n  bungeecord: true\nother: 1\n");
    }

    @Test
    void theProxyPortComesFromItsConfig() throws Exception {
        Files.writeString(dir.resolve(ProxyForwarding.VELOCITY_TOML), "bind = \"127.0.0.1:25600\"\n");
        assertThat(ProxyForwarding.listenPort(dir, Platform.VELOCITY)).isEqualTo(25600);
        Files.writeString(dir.resolve(ProxyForwarding.BUNGEE_CONFIG), "listeners:\n- host: 0.0.0.0:25601\n");
        assertThat(ProxyForwarding.listenPort(dir, Platform.WATERFALL)).isEqualTo(25601);
        assertThat(ProxyForwarding.listenPort(dir.resolve("none"), Platform.VELOCITY)).isEqualTo(25577);
    }

    /** Each backend runs as a fake server: it prints Paper's ready line, echoes commands, ends on stop. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void backendsStartTakeCommandsAndStop() throws Exception {
        Path java = dir.resolve("fake-java");
        Files.writeString(java, "#!/bin/sh\necho \"[00:00:00 INFO]: Done (0.1s)! For help, type \\\"help\\\"\"\n"
                + "while read line; do\n  [ \"$line\" = stop ] && exit 0\n  echo \"ran $line\"\ndone\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
        List<Network.Member> members = Network.plan(dir, List.of("lobby", "game"));
        Network.write(dir, members);
        for (Network.Member m : members) {
            Files.createDirectories(m.dir());
            Files.writeString(m.dir().resolve("paper.jar"), "jar");
            Files.writeString(m.dir().resolve("eula.txt"), "eula=true\n");
        }
        // A backend with no EULA is named, not started.
        Files.writeString(members.get(1).dir().resolve("eula.txt"), "eula=false\n");
        try (Network network = new Network()) {
            assertThat(network.start(dir, java.toString(), Duration.ofSeconds(20)))
                    .startsWith("network: lobby :25566 ready in ")
                    .contains("game not started: the Minecraft EULA is not accepted");
            assertThat(network.start(dir, java.toString(), Duration.ofSeconds(20))).contains("lobby :25566 running");
            network.command("lobby", "say hi");
            Path console = Network.console(dir, "lobby");
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!Files.readString(console).contains("ran say hi") && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(console).content().contains("ran say hi");
            assertThatThrownBy(() -> network.command("game", "list")).hasMessage("backend game is not running: "
                    + "running are lobby");
            assertThat(network.stop(Duration.ofSeconds(10))).isEqualTo("stopped backends lobby");
            assertThat(network.running()).isFalse();
        }
    }
}
