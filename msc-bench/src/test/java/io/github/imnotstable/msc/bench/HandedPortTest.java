package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #580 in msc-bench: a port handed to a child stays the test's until the child listens on it. */
class HandedPortTest {

    @TempDir
    Path dir;

    @Test
    void theChildListensOnTheHeldPortThatOthersCannotBind() throws IOException {
        Assumptions.assumeFalse(System.getProperty("os.name", "").startsWith("Windows"), "let go at once on Windows");
        try (HandedPort handed = HandedPort.reserve()) {
            try (Socket stranger = new Socket()) {
                assertThatThrownBy(() -> stranger.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                        handed.port()))).isInstanceOf(BindException.class);
            }
            Process child = PortHolder.start(dir, handed.port(), dir.resolve("port"));
            try {
                assertThat(PortHolder.awaitPort(child, dir.resolve("port"))).isEqualTo(handed.port());
            } finally {
                child.destroyForcibly();
            }
        }
    }
}
