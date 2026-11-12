package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.Platform;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServerSetupTest {
    @Test
    void platformsThatShareAnAgentInstallItUnderTheAgentsName() {
        assertThat(ServerSetup.installedAgentName(Platform.QUILT)).isEqualTo("msc-agent-fabric.jar");
        assertThat(ServerSetup.installedAgentName(Platform.PURPUR)).isEqualTo("msc-agent-paper.jar");
        assertThat(ServerSetup.installedAgentName(Platform.NEOFORGE)).isEqualTo("msc-agent-neoforge.jar");
    }

    @Test
    void agentJarsMatchByWholeWordSoNeoForgeIsNotForge() {
        assertThat(ServerSetup.namesAgent(Path.of("msc-agent-neoforge-0.1.0.jar"), "forge")).isFalse();
        assertThat(ServerSetup.namesAgent(Path.of("msc-agent-neoforge-0.1.0.jar"), "neoforge")).isTrue();
        assertThat(ServerSetup.namesAgent(Path.of("msc-agent-forge-0.1.0.jar"), "forge")).isTrue();
        assertThat(ServerSetup.namesAgent(Path.of("msc_agent_fabric.jar"), "fabric")).isTrue();
    }
}
