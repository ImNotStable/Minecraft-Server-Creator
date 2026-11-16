package io.github.imnotstable.msc.process;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.BindException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hang fails
class ClosedPortTest {
    @Test
    void refusesConnectionsAndCannotBeTakenWhileHeld() throws Exception {
        try (ClosedPort closed = ClosedPort.reserve(); Socket client = new Socket()) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            assertThatThrownBy(() -> client.connect(new InetSocketAddress(loopback, closed.port()), 30_000))
                    .isInstanceOf(ConnectException.class);
            assertThatThrownBy(() -> {
                try (ServerSocket taken = new ServerSocket()) {
                    taken.bind(new InetSocketAddress(loopback, closed.port()));
                }
            }).isInstanceOf(BindException.class);
        }
    }
}
