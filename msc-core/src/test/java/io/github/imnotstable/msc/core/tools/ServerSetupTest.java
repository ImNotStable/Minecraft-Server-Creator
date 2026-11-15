package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.Platform;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerSetupTest {
    @Test
    void platformsThatShareAnAgentInstallItUnderTheAgentsName() {
        assertThat(Platform.QUILT.agentJar()).isEqualTo("msc-agent-fabric.jar");
        assertThat(Platform.PURPUR.agentJar()).isEqualTo("msc-agent-paper.jar");
        assertThat(Platform.NEOFORGE.agentJar()).isEqualTo("msc-agent-neoforge.jar");
    }

    /** #460: the warning names the agent jar a build produces, which for Quilt and Purpur is another platform's. */
    @Test
    void theMissingAgentWarningNamesTheAgentEachPlatformRuns() {
        Path dir = Path.of("ws", ".msc", "agent");
        for (Platform platform : Platform.values()) {
            String warning = ServerSetup.missingAgent(platform, dir);
            String jar = switch (platform) {
                case PAPER, PURPUR, FOLIA -> "msc-agent-paper";
                case FABRIC, QUILT -> "msc-agent-fabric";
                case NEOFORGE -> "msc-agent-neoforge";
                case FORGE -> "msc-agent-forge";
                case MINESTOM -> "msc-agent-minestom";
                case VELOCITY -> null; // a proxy runs no agent: server_setup never looks for one
            };
            if (jar == null) {
                continue;
            }
            assertThat(warning).as(platform.id()).startsWith("warning: msc " + platform.agent() + " agent not found")
                    .contains("Put " + jar + ".jar in " + dir).doesNotContain("\n");
        }
        assertThat(ServerSetup.missingAgent(Platform.QUILT, dir)).contains("Quilt runs the fabric agent")
                .doesNotContain("msc quilt");
        assertThat(ServerSetup.missingAgent(Platform.PURPUR, dir)).contains("Purpur runs the paper agent")
                .doesNotContain("msc purpur");
    }

    @Test
    void agentJarsMatchByWholeWordSoNeoForgeIsNotForge() {
        assertThat(ServerSetup.namesAgent(Path.of("msc-agent-neoforge-0.1.0.jar"), "forge")).isFalse();
        assertThat(ServerSetup.namesAgent(Path.of("msc-agent-neoforge-0.1.0.jar"), "neoforge")).isTrue();
        assertThat(ServerSetup.namesAgent(Path.of("msc-agent-forge-0.1.0.jar"), "forge")).isTrue();
        assertThat(ServerSetup.namesAgent(Path.of("msc_agent_fabric.jar"), "fabric")).isTrue();
    }
}
