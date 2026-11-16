package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0054: the one list of ports a workspace uses for anything but the pack, each with what uses it. */
class WorkspacePortsTest {
    @TempDir
    Path dir;

    @Test
    void theDefaultsAreReservedWithoutAServerFolder() {
        WorkspacePorts ports = WorkspacePorts.of(dir.resolve("none"), Map.of());
        assertThat(ports.uses().keySet()).containsExactlyInAnyOrder(25565, 25575, 25577, 19132);
        assertThat(ports.use(25577)).isEqualTo("the dev proxy's port (Velocity's default)");
        assertThat(ports.reserved(25566)).isFalse();
        assertThat(WorkspacePorts.DEFAULTS.uses().keySet()).isEqualTo(ports.uses().keySet());
    }

    @Test
    void theServerFolderAndTheCoreAddTheirPorts() throws Exception {
        Files.writeString(dir.resolve("server.properties"),
                "server-port=26000\nrcon.port=26001\nquery.port=26002\nenable-query=true\n");
        Path geyser = BedrockConfig.file(dir);
        Files.createDirectories(geyser.getParent());
        Files.writeString(geyser, "bedrock:\n  address: 0.0.0.0\n  port: 19200\n");
        Files.writeString(dir.resolve("velocity.toml"), """
                bind = "0.0.0.0:26100"
                [servers]
                lobby = "127.0.0.1:26101"
                [query]
                port = 26102
                """);
        WorkspacePorts ports = WorkspacePorts.of(dir, Map.of(26200, "MSC's agent link port"));
        assertThat(ports.uses().keySet()).contains(25565, 25575, 25577, 19132, 26000, 26001, 26002, 19200, 26100,
                26101, 26102, 26200);
        assertThat(ports.use(26000)).isEqualTo("the server's port (server-port in server.properties)");
        assertThat(ports.use(26001)).isEqualTo("the server's RCON port (rcon.port in server.properties)");
        assertThat(ports.use(19200)).contains("bedrock.port");
        assertThat(ports.use(26101)).isEqualTo("a port in velocity.toml");
        assertThat(ports.use(26200)).isEqualTo("MSC's agent link port");
    }

    @Test
    void aServerOnTheDefaultPortNamesItByItsSetting() throws Exception {
        Files.writeString(dir.resolve("server.properties"), "server-port=25565\nrcon.port=oops\n");
        WorkspacePorts ports = WorkspacePorts.of(dir, Map.of());
        assertThat(ports.use(25565)).isEqualTo("the server's port (server-port in server.properties)");
        // A port that is not a number is the server's default, as the server reads it.
        assertThat(ports.use(25575)).isEqualTo("the server's RCON port (rcon.port in server.properties)");
    }
}
