package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AgentLinkTest {
    @Test
    void staysIdleWithoutACoreAddressAndDropsEvents() throws InterruptedException {
        List<String> logged = new CopyOnWriteArrayList<>();
        AgentLog log = new AgentLog() {
            @Override
            public void debug(String message) {
                logged.add("debug " + message);
            }

            @Override
            public void info(String message) {
                logged.add("info " + message);
            }

            @Override
            public void warn(String message) {
                logged.add("warn " + message);
            }

            @Override
            public void error(String message) {
                logged.add("error " + message);
            }
        };
        AgentLink link = new AgentLink(new AgentInfo("test", "1", "26.3", List.of("server.info")), new OpRouter(),
                Optional::empty, log);

        link.start();
        for (int i = 0; i < 100 && logged.isEmpty(); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        link.serverLoaded();
        link.playerJoined("Alex", UUID.randomUUID());
        link.stop();

        assertThat(logged).containsExactly("info MSC_CORE_ADDRESS is not set; agent is idle.");
        assertThat(link.session()).isNull();
        assertThat(link.stopping()).isTrue();
    }
}
