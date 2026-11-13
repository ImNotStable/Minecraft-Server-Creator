package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.imnotstable.msc.bench.ClosedPort;
import io.github.imnotstable.msc.bench.FakeRconServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ServerManagerTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElse("java");

    @TempDir
    Path serverDir;

    /** The game port: held closed for the whole test, so nothing on the machine answers on it. */
    private ClosedPort gamePort;

    @BeforeEach
    void reserveGamePort() throws IOException {
        gamePort = ClosedPort.reserve();
    }

    @AfterEach
    void releaseGamePort() throws IOException {
        gamePort.close();
    }

    private BenchConfig.Server config(List<String> command, int rconPort) {
        return new BenchConfig.Server(command, null, List.of(), null, true, gamePort.port(), rconPort, "pw", 1);
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

        try (ClosedPort rconPort = ClosedPort.reserve();
             ServerManager server = new ServerManager(serverDir, config(command, rconPort.port()), Map.of())) {
            assertThat(catchThrowable(server::rcon)).hasMessageContaining("did not open RCON within 1s");
            assertThat(liveServers()).as("the server that did not open RCON is stopped").isZero();

            assertThat(catchThrowable(server::rcon)).hasMessageContaining("did not open RCON within 1s");
            assertThat(liveServers()).as("a later check starts no second server").isZero();
        }
    }

    @Test
    void closeWaitsUntilAServerItDidNotStartHasStopped() throws IOException {
        AtomicBoolean stopped = new AtomicBoolean();
        FakeRconServer[] holder = new FakeRconServer[1];
        holder[0] = new FakeRconServer("pw", command -> {
            if (command.equals("stop")) {
                Thread.ofVirtual().start(() -> {
                    try {
                        Thread.sleep(1000);
                        stopped.set(true);
                        holder[0].close();
                    } catch (InterruptedException | IOException e) {
                        // test ends either way
                    }
                });
            }
            return "";
        });
        try (FakeRconServer rconServer = holder[0]) {
            ServerManager server = new ServerManager(serverDir, config(List.of("unused"), rconServer.port()), Map.of());
            server.rcon();

            server.close();

            assertThat(rconServer.commands()).containsExactly("stop");
            assertThat(stopped).as("close() returned only after the server let go of its ports").isTrue();
        }
    }

    @Test
    void aDisconnectLetsTheChecksReachAServerTheAgentRestarted() throws IOException {
        FakeRconServer before = new FakeRconServer("pw", command -> "");
        int port = before.port();
        ServerManager server = new ServerManager(serverDir, config(List.of("unused"), port), Map.of());
        server.rcon().command("setup");
        server.disconnect();
        before.close();

        FakeRconServer[] holder = new FakeRconServer[1];
        holder[0] = new FakeRconServer(port, "pw", command -> {
            if (command.equals("stop")) {
                try {
                    holder[0].close(); // a real server lets go of its port on stop
                } catch (IOException e) {
                    // test ends either way
                }
            }
            return "ok";
        });
        try (FakeRconServer after = holder[0]) {
            assertThat(server.rcon().command("check")).isEqualTo("ok");
            long start = System.nanoTime();
            server.close();

            assertThat(after.commands()).containsExactly("check", "stop");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("close() returns once the port is free")
                    .isLessThan(Duration.ofSeconds(10));
        }
    }

    @Test
    void clearPortsStopsAServerLeftFromAnEarlierRun() throws IOException {
        FakeRconServer[] holder = new FakeRconServer[1];
        holder[0] = new FakeRconServer("pw", command -> {
            if (command.equals("stop")) {
                try {
                    holder[0].close();
                } catch (IOException e) {
                    // test ends either way
                }
            }
            return "";
        });
        try (FakeRconServer orphan = holder[0]) {
            ServerManager.clearPorts(config(List.of("unused"), orphan.port()), Duration.ofSeconds(10));

            assertThat(orphan.commands()).containsExactly("stop");
        }
    }

    @Test
    void clearPortsRefusesPortsItCannotFree() throws IOException {
        // Something that is not a bench server: accepts connections but speaks no RCON.
        try (ServerSocket taken = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            List<Socket> held = new CopyOnWriteArrayList<>();
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        held.add(taken.accept());
                    }
                } catch (IOException closed) {
                    // test over
                }
            });
            Throwable refused = catchThrowable(() -> ServerManager.clearPorts(
                    config(List.of("unused"), taken.getLocalPort()), Duration.ofMillis(200)));

            assertThat(refused).isInstanceOf(IOException.class).hasMessageContaining("port " + taken.getLocalPort());
        }
    }

    @ParameterizedTest
    @CsvSource({"PT0.2S, PT0.2S", "PT5S, PT5S", "PT60S, PT5S", "PT0S, PT0.001S", "PT-1S, PT0.001S"})
    void theLoginWaitBeforeAStopNeverOutlastsTheCallersWait(Duration wait, Duration login) {
        // clearPorts(config, wait) sent stop with a fixed 5 s login, so a silent port holder cost 5 s whatever the wait.
        assertThat(ServerManager.loginTimeout(wait)).isEqualTo(login);
    }
}
