package io.github.imnotstable.msc.core.texture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PackHostTest {
    @Test
    void waitsForAPortThatIsLetGoAMomentLater() throws Exception {
        ServerSocket holder = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        int port = holder.getLocalPort();
        CompletableFuture.runAsync(() -> {
            try {
                holder.close();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));
        HttpServer server = PackHost.startWithin("127.0.0.1", port, () -> PackHost.Served.NONE, Duration.ofSeconds(5));
        try {
            assertThat(server.getAddress().getPort()).isEqualTo(port);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void givesUpOnAPortThatStaysTaken() throws Exception {
        try (ServerSocket holder = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            assertThatThrownBy(() -> PackHost.startWithin("127.0.0.1", holder.getLocalPort(),
                    () -> PackHost.Served.NONE, Duration.ofMillis(300))).isInstanceOf(BindException.class);
        }
    }
}
