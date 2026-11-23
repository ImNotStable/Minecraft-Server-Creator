package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0046: Geyser's config.yml is written once with a free Bedrock port, then left to the user. */
class BedrockConfigTest {
    @TempDir
    Path server;

    private static BedrockConfig.PortCheck taken(Integer... ports) {
        Set<Integer> busy = Set.of(ports);
        return (host, port) -> !busy.contains(port);
    }

    @Test
    void aNewConfigBindsLikeTheJavaServerOnTheFirstFreePortWithFloodgateSignIn() throws Exception {
        Files.writeString(server.resolve("server.properties"), "server-ip=192.168.0.7\nserver-port=25570\n");

        BedrockConfig.Result r = BedrockConfig.configure(server, taken(19132));

        assertThat(r).isEqualTo(new BedrockConfig.Result("192.168.0.7", 19133, BedrockConfig.Change.WROTE));
        List<String> lines = Files.readAllLines(BedrockConfig.file(server));
        assertThat(BedrockConfig.get(lines, "bedrock", "address")).isEqualTo("192.168.0.7");
        assertThat(BedrockConfig.get(lines, "bedrock", "port")).isEqualTo("19133");
        assertThat(BedrockConfig.get(lines, "java", "auth-type")).isEqualTo("floodgate");
        assertThat(lines).contains("config-version: " + BedrockConfig.CONFIG_VERSION);
    }

    @Test
    void withoutServerIpGeyserListensOnEveryAddressAndAnIpv6OneIsQuoted() throws Exception {
        assertThat(BedrockConfig.configure(server, taken()).address()).isEqualTo("0.0.0.0");
        assertThat(BedrockConfig.bindAddress(Map.of("server-ip", "[fd00::5]"))).isEqualTo("fd00::5");
        // Geyser 2.12 times every Bedrock player out on loopback (ADR 0088).
        assertThat(BedrockConfig.bindAddress(Map.of("server-ip", "[::1]"))).isEqualTo("0.0.0.0");
        assertThat(BedrockConfig.bindAddress(Map.of("server-ip", "127.0.0.1"))).isEqualTo("0.0.0.0");
        assertThat(BedrockConfig.fresh("::1", 19132)).contains("  address: \"::1\"\n");
        assertThat(BedrockConfig.get(BedrockConfig.fresh("::1", 19132).lines().toList(), "bedrock", "address"))
                .isEqualTo("::1");
    }

    @Test
    void aRerunKeepsTheUsersFileAndMovesOnlyATakenPort() throws Exception {
        Path file = Files.createDirectories(BedrockConfig.file(server).getParent()).resolve("config.yml");
        String edited = """
                # mine
                bedrock:
                    address: 0.0.0.0   # LAN
                    port: 19200 # chosen
                    motd: x
                java:
                    auth-type: online
                config-version: 12
                """;
        Files.writeString(file, edited);

        assertThat(BedrockConfig.configure(server, taken()))
                .isEqualTo(new BedrockConfig.Result("0.0.0.0", 19200, BedrockConfig.Change.KEPT));
        assertThat(file).hasContent(edited);

        BedrockConfig.Result moved = BedrockConfig.configure(server, taken(19200, 19132));

        assertThat(moved).isEqualTo(new BedrockConfig.Result("0.0.0.0", 19133, BedrockConfig.Change.MOVED));
        assertThat(file).hasContent(edited.replace("port: 19200 # chosen", "port: 19133 # chosen"));
    }

    @Test
    void noFreePortIsAOneLineRefusal() {
        assertThatThrownBy(() -> BedrockConfig.freePort("127.0.0.1", 19132, (h, p) -> false))
                .isInstanceOf(ToolException.class).hasMessage("no free UDP port for Bedrock from 19132 to 19231 on "
                        + "127.0.0.1: free one, or set bedrock.port in plugins/Geyser-Spigot/config.yml");
        java.util.List<Integer> seen = new java.util.ArrayList<>();
        assertThatThrownBy(() -> BedrockConfig.freePort("::1", 65533, (h, p) -> seen.add(p) && false))
                .hasMessageStartingWith("no free UDP port for Bedrock from 65533 to 65535 on ::1");
        assertThat(seen).containsExactly(65533, 65534, 65535);
    }

    @Test
    void setReplacesInPlaceOrAddsTheKeyOrTheSection() {
        List<String> lines = List.of("bedrock:", "  # port: 1", "  port: 19132", "  nested:", "    port: 5", "java:",
                "  auth-type: floodgate");
        assertThat(BedrockConfig.get(lines, "bedrock", "port")).isEqualTo("19132");
        assertThat(BedrockConfig.get(lines, "java", "port")).isNull();
        assertThat(BedrockConfig.get(lines, "remote", "port")).isNull();
        assertThat(BedrockConfig.set(lines, "bedrock", "port", "7")).containsExactly("bedrock:", "  # port: 1",
                "  port: 7", "  nested:", "    port: 5", "java:", "  auth-type: floodgate");
        assertThat(BedrockConfig.set(lines, "java", "port", "25565")).containsExactly("bedrock:", "  # port: 1",
                "  port: 19132", "  nested:", "    port: 5", "java:", "  port: 25565", "  auth-type: floodgate");
        assertThat(BedrockConfig.set(List.of("a: 1"), "bedrock", "port", "7"))
                .containsExactly("a: 1", "bedrock:", "  port: 7");
    }

    @Test
    void theStartLineNeedsGeyserInPluginsAndNamesBothPorts() throws Exception {
        assertThat(BedrockConfig.line(server)).isNull();
        Files.writeString(server.resolve("server.properties"), "server-ip=127.0.0.1\nserver-port=25570\n");
        BedrockConfig.configure(server, taken());
        Files.writeString(server.resolve("plugins/Geyser-Spigot.jar"), "jar");

        assertThat(BedrockConfig.line(server)).isEqualTo("bedrock: UDP 0.0.0.0:19132 (Geyser, fronting Java port "
                + "25570; Floodgate sign-in, open to the network)");
        assertThat(BedrockConfig.describe("127.0.0.1", 19132, Map.of())).endsWith("; bedrock.address 0.0.0.0 in "
                + "plugins/Geyser-Spigot/config.yml lets the LAN in)");
        assertThat(BedrockConfig.describe("0.0.0.0", 19133, Map.of())).isEqualTo(
                "bedrock: UDP 0.0.0.0:19133 (Geyser, fronting Java port 25565; Floodgate sign-in, open to the network)");
    }

    @Test
    void aUdpPortHeldByAnotherSocketIsNotFree() throws Exception {
        try (DatagramSocket held = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            assertThat(BedrockConfig.udpFree("127.0.0.1", held.getLocalPort())).isFalse();
        }
        assertThat(BedrockConfig.udpFree("127.0.0.1", 0)).isTrue();
    }
}
