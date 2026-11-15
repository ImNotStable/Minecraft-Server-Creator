package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.RequestHandler;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * #721: a closed CoreServer has let its port go, so the next one binds it at once. A socket closed while a virtual
 * thread is blocked in its accept used to stay bound until that thread woke, and a workspace switch with a fixed
 * MSC_CORE_ADDRESS failed with "Address already in use" about every second time.
 */
class CoreServerCloseTest {
    private static final CoreServer.AgentListener NOBODY = session -> {
    };

    @Test
    void theSamePortBindsAgainRightAfterClose() throws Exception {
        CoreServer server = CoreServer.start(0, "token", RequestHandler.none(), NOBODY, Duration.ofSeconds(1));
        int port = server.port();
        try {
            for (int i = 0; i < 50; i++) {
                // Give the accept loop time to block in accept, as a real core's does between two switches.
                Thread.sleep(2);
                server.close();
                server = CoreServer.start(port, "token", RequestHandler.none(), NOBODY, Duration.ofSeconds(1));
                assertThat(server.port()).isEqualTo(port);
            }
        } finally {
            server.close();
        }
    }
}
