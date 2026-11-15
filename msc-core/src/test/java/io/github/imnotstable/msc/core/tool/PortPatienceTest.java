package io.github.imnotstable.msc.core.tool;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PortPatienceTest {
    @Test
    void aPortThatStaysTakenIsNamed() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = taken.getLocalPort();
            // #727: the error a switch shows must say which port to free.
            assertThatThrownBy(() -> PortPatience.bind(port, Duration.ofMillis(200),
                    () -> new ServerSocket(port, 1, InetAddress.getLoopbackAddress())))
                    .isInstanceOf(BindException.class).hasMessageStartingWith("port " + port + " is taken: ");
        }
    }
}
