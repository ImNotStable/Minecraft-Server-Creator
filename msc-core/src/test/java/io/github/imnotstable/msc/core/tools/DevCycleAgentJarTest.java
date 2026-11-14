package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.Platform;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** #311: dev_cycle never deploys over, or removes, the agent jar server_setup installs. */
class DevCycleAgentJarTest {
    @Test
    void theAgentJarOfEachPlatformIsKnownIgnoringCaseAndOnlyThere() {
        for (Platform platform : Platform.values()) {
            String agent = platform.agentJar();
            assertThat(DevCycle.isAgentJar(platform, agent)).as(agent).isTrue();
            assertThat(DevCycle.isAgentJar(platform, agent.toUpperCase(java.util.Locale.ROOT))).as(agent).isTrue();
            String deployed = DevCycle.deployName(Path.of(agent.replace(".jar", "-0.1.0-SNAPSHOT.jar")), Path.of("p")) + ".jar";
            assertThat(DevCycle.isAgentJar(platform, deployed)).as(deployed).isTrue();
            Arrays.stream(Platform.values()).filter(other -> !other.agent().equals(platform.agent()))
                    .forEach(other -> assertThat(DevCycle.isAgentJar(platform, other.agentJar())).as(other.agentJar()).isFalse());
        }
        assertThat(DevCycle.isAgentJar(Platform.PAPER, "msc-agent-paper-tools.jar")).isFalse();
        assertThat(DevCycle.isAgentJar(Platform.QUILT, "msc-agent-fabric.jar")).isTrue();
        assertThat(DevCycle.isAgentJar(Platform.PURPUR, "msc-agent-paper.jar")).isTrue();
    }
}
