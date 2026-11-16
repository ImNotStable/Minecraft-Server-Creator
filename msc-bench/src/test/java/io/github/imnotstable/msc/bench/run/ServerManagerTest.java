package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.imnotstable.msc.bench.FakeRconMain;
import io.github.imnotstable.msc.bench.HandedPort;
import io.github.imnotstable.msc.bench.PortHolder;
import io.github.imnotstable.msc.process.ClosedPort;
import io.github.imnotstable.msc.process.rcon.FakeRconServer;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ServerManagerTest {

    /** The run whose processes the ports step may kill. */
    private static final AttemptProcesses RUN = AttemptProcesses.newRun();

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
    void closeStopsAServerItStartedAndDisconnectedFrom() throws IOException {
        // After the setup the runner disconnects; when no check reconnects (the agent could not start, a usage limit)
        // close() sent no stop, waited 60 s for a server never told to go, and killed it.
        Files.writeString(serverDir.resolve("paper.jar"), "stands in for paper");
        Files.writeString(serverDir.resolve("eula.txt"), "eula=true");
        ServerManager server;
        try (HandedPort rconPort = HandedPort.reserve()) {
            int port = rconPort.port();
            List<String> command = List.of(JAVA, "-Dmarker=" + serverDir, "-cp", System.getProperty("java.class.path"),
                    FakeRconMain.class.getName(), Integer.toString(port), "pw");
            BenchConfig.Server config = new BenchConfig.Server(command, null, List.of(), null, true, gamePort.port(),
                    port, "pw", 60);
            server = new ServerManager(serverDir, config, Map.of());
            server.rcon().command("setup");
        }
        server.disconnect();

        server.close();

        assertThat(serverDir.resolve(FakeRconMain.STOPPED)).as("the server was told to stop, not killed").exists();
        assertThat(liveServers()).isZero();
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
            ServerManager.clearPorts(config(List.of("unused"), orphan.port()), RUN, serverDir,
                    Duration.ofSeconds(10));

            assertThat(orphan.commands()).containsExactly("stop");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"pa\\ss|login refused", "LONG|1446"})
    @Timeout(180)
    void aRefusedLoginFailsTheStartAtOnce(String password, String reason) throws IOException {
        // #393: a refused login counted as "not up yet", so the start waited its whole timeout (here 600 s) and
        // blamed the server for not opening RCON.
        String configured = password.equals("LONG") ? "p".repeat(1447) : password;
        Files.writeString(serverDir.resolve("paper.jar"), "stands in for paper");
        Files.writeString(serverDir.resolve("eula.txt"), "eula=true");
        try (HandedPort rconPort = HandedPort.reserve()) {
            refusedLogin(rconPort.port(), configured, reason);
        }
    }

    private void refusedLogin(int port, String configured, String reason) throws IOException {
        List<String> command = List.of(JAVA, "-Dmarker=" + serverDir, "-cp", System.getProperty("java.class.path"),
                FakeRconMain.class.getName(), Integer.toString(port), "pass");
        BenchConfig.Server config = new BenchConfig.Server(command, null, List.of(), null, true, gamePort.port(), port,
                configured, 600);
        try (ServerManager server = new ServerManager(serverDir, config, Map.of())) {
            assertThat(catchThrowable(server::rcon)).isInstanceOf(IOException.class)
                    .hasMessageContaining(reason).hasMessageNotContaining("did not open RCON");
            assertThat(liveServers()).as("the refused server is stopped").isZero();
            assertThat(catchThrowable(server::rcon)).hasMessageContaining(reason);
        }
    }

    @Test
    @Timeout(120)
    void clearPortsReportsAServerThatRefusesTheLogin() throws IOException {
        try (FakeRconServer other = new FakeRconServer("not the bench's", command -> "")) {
            Throwable refused = catchThrowable(() -> ServerManager.clearPorts(config(List.of("unused"), other.port()),
                    RUN, serverDir, Duration.ofSeconds(600)));
            assertThat(refused).isInstanceOf(IOException.class).hasMessageContaining("login refused")
                    .hasMessageContaining("port " + other.port());
            assertThat(other.commands()).isEmpty();
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
                    config(List.of("unused"), taken.getLocalPort()), RUN, serverDir, Duration.ofMillis(200)));

            assertThat(refused).isInstanceOf(IOException.class).hasMessageContaining("port " + taken.getLocalPort());
        }
    }

    /**
     * #577, #587: a Minestom server has no RCON, so a stop never reaches it. One this run started for an attempt in
     * the folder it is given is killed and its port freed; any other, even one running in that folder, is refused at
     * once, without the minute's wait for a stop nobody was sent, and left running.
     */
    @Test
    @Timeout(120)
    void clearPortsKillsAServerWithoutRconOnlyWhenThisRunStartedItForTheGivenFolder() throws IOException {
        Assumptions.assumeTrue(AttemptProcesses.canProve(), "this system shows no process's environment");
        Path attempt = Files.createDirectories(serverDir.resolve("attempts/game/baseline-1"));
        Path workspace = Files.createDirectories(attempt.resolve("workspace"));
        Process ours = PortHolder.start(serverDir, 0, serverDir.resolve("ours.port"), RUN.marks(attempt));
        // In the attempt's workspace, naming it, but started by someone else: a user's server, say.
        Process theirs = PortHolder.start(workspace, 0, workspace.resolve("port"), workspace.toString());
        try {
            int ourPort = PortHolder.awaitPort(ours, serverDir.resolve("ours.port"));
            int theirPort = PortHolder.awaitPort(theirs, workspace.resolve("port"));
            long start = System.nanoTime();
            Throwable refused = catchThrowable(() -> ServerManager.clearPorts(
                    new BenchConfig.Server(List.of("unused"), null, List.of(), null, true, theirPort, gamePort.port(),
                            "pw", 1), RUN, serverDir.resolve("attempts"), Duration.ofSeconds(600)));
            assertThat(refused).isInstanceOf(IOException.class).hasMessageContaining("port " + theirPort);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("no wait for a stop never sent")
                    .isLessThan(Duration.ofSeconds(60));
            assertThat(theirs.isAlive()).as("a process this run did not start is never killed").isTrue();

            ServerManager.clearPorts(new BenchConfig.Server(List.of("unused"), null, List.of(), null, true, ourPort,
                    gamePort.port(), "pw", 1), RUN, serverDir.resolve("attempts"), Duration.ofSeconds(60));
            assertThat(ours.isAlive()).isFalse();
        } finally {
            ours.destroyForcibly();
            theirs.destroyForcibly();
        }
    }

    @ParameterizedTest
    @CsvSource({"PT0.2S, PT0.2S", "PT5S, PT5S", "PT60S, PT5S", "PT0S, PT0.001S", "PT-1S, PT0.001S"})
    void theLoginWaitBeforeAStopNeverOutlastsTheCallersWait(Duration wait, Duration login) {
        // clearPorts(config, wait) sent stop with a fixed 5 s login, so a silent port holder cost 5 s whatever the wait.
        assertThat(ServerManager.loginTimeout(wait)).isEqualTo(login);
    }
}
