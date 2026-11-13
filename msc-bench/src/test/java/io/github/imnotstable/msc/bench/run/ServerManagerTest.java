package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerManagerTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElse("java");

    @TempDir
    Path serverDir;

    private BenchConfig.Server config(List<String> command, int rconPort) throws IOException {
        return new BenchConfig.Server(command, null, List.of(), null, true, freePort(), rconPort, "pw", 1);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private long liveServers() {
        String marker = "-Dmarker=" + serverDir;
        return ProcessHandle.current().descendants().filter(ProcessHandle::isAlive)
                .filter(p -> p.info().commandLine().orElse("").contains(marker)).count();
    }

    @Test
    void aStartThatTimesOutStopsItsServerAndIsNotRepeated() throws IOException {
        Files.writeString(serverDir.resolve("paper.jar"), "stands in for paper");
        Files.writeString(serverDir.resolve("eula.txt"), "eula=true");
        // Stays alive (waits on standard input) and never opens RCON. The marker comes first: the OS cuts long
        // command lines.
        List<String> command = List.of(JAVA, "-Dmarker=" + serverDir, "-cp", System.getProperty("java.class.path"),
                "io.github.imnotstable.msc.bench.FakeServer");

        try (ServerManager server = new ServerManager(serverDir, config(command, freePort()), Map.of())) {
            assertThat(catchThrowable(server::rcon)).hasMessageContaining("did not open RCON within 1s");
            assertThat(liveServers()).as("the server that did not open RCON is stopped").isZero();

            assertThat(catchThrowable(server::rcon)).hasMessageContaining("did not open RCON within 1s");
            assertThat(liveServers()).as("a later check starts no second server").isZero();
        }
    }
}
