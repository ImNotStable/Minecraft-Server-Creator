package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.ClosedPort;
import io.github.imnotstable.msc.process.rcon.FakeRconServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ServerManagerTest {
    @TempDir
    Path workspace;
    private AgentHub hub;
    private ServerManager server;

    private final List<Process> leftovers = new java.util.ArrayList<>();

    @AfterEach
    void close() throws Exception {
        leftovers.forEach(Process::destroyForcibly);
        if (server != null) {
            server.close();
        }
        if (hub != null) {
            hub.close();
        }
    }

    private ServerManager manager(Class<?> main) throws Exception {
        hub = new AgentHub(0, "token");
        server = new ServerManager(CoreConfig.forWorkspace(workspace).withServerCommand(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName())), hub);
        return server;
    }

    @Test
    void aStartThatEndsInACrashReturnsWithTheWholeTrace() throws Exception {
        ServerManager m = manager(CrashingServer.class);
        ServerManager.StartResult r = m.start(Duration.ofSeconds(60));
        assertThat(r.ready()).isFalse();
        assertThat(r.exited()).isTrue();
        List<String> lines = m.log().since(r.logMark());
        // The JVM may print a notice first (JAVA_TOOL_OPTIONS): count the trace's own lines.
        assertThat(lines.stream().filter(l -> l.startsWith("\tat com.example.Mod.")).count())
                .isEqualTo(CrashingServer.LINES - 1);
        assertThat(lines.getLast()).isEqualTo(CrashingServer.LAST);
    }

    @Test
    void aServerThatDiesWhileTheAgentIsAwaitedIsNotReady() throws Exception {
        ServerManager m = manager(DoneThenCrashServer.class);
        // Paper with the agent installed: start waits for it after "Done", and the server dies meanwhile (#337).
        assertThat(m.platform()).isEqualTo(Platform.PAPER);
        Path agent = m.serverDir().resolve(Platform.PAPER.addonDir()).resolve(Platform.PAPER.agentJar());
        Files.createDirectories(agent.getParent());
        Files.writeString(agent, "agent");
        ServerManager.StartResult r = m.start(Duration.ofSeconds(60));
        assertThat(r.ready()).isFalse();
        assertThat(r.exited()).isTrue();
        assertThat(r.sawDone()).isTrue();
        assertThat(m.log().since(r.logMark())).endsWith(DoneThenCrashServer.LAST);
    }

    @Test
    void aStopLeavesThePidFileOfAServerStartedWhileItWaited() throws Exception {
        ServerManager m = manager(QuietServer.class);
        assertThat(m.start(Duration.ofSeconds(60)).ready()).isTrue();
        Process first = m.process();
        assertThat(m.stop(Duration.ofSeconds(30))).isTrue();
        assertThat(m.pidFile()).doesNotExist();
        // The race of #323 in order: the old server exited, a new one started, then the old stop cleans up.
        assertThat(m.start(Duration.ofSeconds(60)).ready()).isTrue();
        m.released(first);
        assertThat(m.pidFile()).exists();
        assertThat(m.stop(Duration.ofSeconds(30))).isTrue();
        assertThat(m.pidFile()).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rcon.port=70000", "rcon.port=-1", "rcon.password=\\uZZZZ", "enable-rcon=false"})
    void aLeftoverWithoutUsableRconIsKilledAndForgotten(String line) throws Exception {
        // #394: rcon.port=70000 threw out of stopLeftover after the pid file was gone, and the server kept running.
        ServerManager m = manager(QuietServer.class);
        Process leftover = leftover(m);
        Files.createDirectories(m.serverDir());
        // A port nothing answers on before the line under test (a later key wins), so no case can fall back to the
        // default 25575 and reach an RCON server on this machine.
        try (ClosedPort closed = ClosedPort.reserve()) {
            Files.writeString(m.serverDir().resolve("server.properties"),
                    "enable-rcon=true\nrcon.password=pw\nrcon.port=" + closed.port() + "\n" + line + "\n");
            assertThat(m.stopLeftover()).contains("pid " + leftover.pid(), "killed");
        }
        assertThat(leftover.waitFor(10, TimeUnit.SECONDS)).as("exit seen through the test's own handle").isTrue();
        assertThat(m.pidFile()).doesNotExist();
        assertThat(m.stopLeftover()).isNull();
    }

    @Test
    void aLeftoverIsAskedOverRconWhereTheServerBindsIt() throws Exception {
        // #396: enable-rcon is read in any case and the host is server-ip, as the server does.
        ServerManager m = manager(QuietServer.class);
        Process leftover = leftover(m);
        try (FakeRconServer rcon = new FakeRconServer("pässwörd", cmd -> {
            leftover.destroy(); // stands in for the server saving and exiting
            return "Stopping the server";
        })) {
            Files.createDirectories(m.serverDir());
            Files.writeString(m.serverDir().resolve("server.properties"), "enable-rcon=TRUE\nserver-ip=127.0.0.1\n"
                    + "rcon.port=" + rcon.port() + "\nrcon.password=pässwörd\n", StandardCharsets.UTF_8);
            assertThat(m.stopLeftover()).contains("pid " + leftover.pid(), "saved over RCON");
            assertThat(rcon.commands()).containsExactly("stop");
        }
        assertThat(m.pidFile()).doesNotExist();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES) // a hang guard: the long waits below must never be waited out
    void aLeftoverThatSurvivesTheKillStopsEveryStartAndIsNotWaitedOutTwice() throws Exception {
        // #408: the start went on, its server died on the world lock, and its pid replaced the leftover's.
        ServerManager m = manager(QuietServer.class);
        FakeLeftover leftover = FakeLeftover.install(m, Duration.ofMillis(1));
        String pid = "pid " + leftover.pid();
        assertThatThrownBy(m::stopLeftover).isInstanceOf(ToolException.class)
                .hasMessageContaining(pid).hasMessageContaining("over RCON").hasMessageContaining("is still running")
                .hasMessageContaining("Nothing was started").hasMessageContaining("taskkill /F /PID " + leftover.pid())
                .hasMessageContaining("kill -9 " + leftover.pid()).hasMessageContaining("server_stop");
        assertThat(leftover.destroys).hasValue(1);
        assertThat(leftover.kills).hasValue(1);
        assertThat(Files.readString(m.pidFile())).isEqualTo(leftover.pid() + " 1 resisting\n");

        // No start launches while it runs, and its pid file stays.
        assertThatThrownBy(() -> m.start(Duration.ofSeconds(60))).isInstanceOf(ToolException.class)
                .hasMessageContaining(pid).hasMessageContaining("survived a whole stop before");
        assertThat(m.process()).isNull();
        assertThat(m.leftoverPid()).isEqualTo(leftover.pid());

        // The next start's stop only kills it again: none of the hour-long waits of the whole stop.
        leftover.waitFor(m, Duration.ofHours(1));
        int waits = leftover.waits.get();
        assertThatThrownBy(m::stopLeftover).isInstanceOf(ToolException.class).hasMessageContaining(pid)
                .hasMessageContaining("survived a whole stop before");
        assertThat(leftover.destroys).hasValue(1);
        assertThat(leftover.kills).hasValue(2);
        assertThat(leftover.waits.get() - waits).as("waits: the short one only").isEqualTo(1);

        // server_stop tries the whole stop again, and says it failed.
        leftover.waitFor(m, Duration.ofMillis(1));
        assertThatThrownBy(() -> m.stopLeftover(true)).isInstanceOf(ToolException.class).hasMessageContaining(pid)
                .hasMessageContaining("over RCON");
        assertThat(leftover.destroys).hasValue(2);
        assertThat(leftover.kills).hasValue(3);
        assertThat(Files.readString(m.pidFile())).isEqualTo(leftover.pid() + " 1 resisting\n");

        // Once a kill works it is gone, and so is its record.
        leftover.diesWhenKilled();
        assertThat(m.stopLeftover()).contains(pid, "killed");
        assertThat(m.pidFile()).doesNotExist();
        assertThat(m.stopLeftover()).isNull();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES) // a hang guard: the hour-long wait below must never be waited out
    void aSlowLeftoverStopBlocksNeitherTheStatusNorTheRefusals() throws Exception {
        // #712: stopLeftover waited for the leftover to exit on the monitor msc_status and every refusal need.
        ServerManager m = manager(QuietServer.class);
        FakeLeftover leftover = FakeLeftover.install(m, Duration.ofHours(1));
        java.util.concurrent.CompletableFuture<String> stop = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return m.stopLeftover(true);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            whileALeftoverStopWaits(m, leftover, stop);
        } finally {
            leftover.exit(); // never left waiting, also when an assertion failed
        }
        assertThat(stop.get(60, TimeUnit.SECONDS)).contains("pid " + leftover.pid());
        assertThat(m.pidFile()).doesNotExist();
    }

    private static void whileALeftoverStopWaits(ServerManager m, FakeLeftover leftover,
            java.util.concurrent.CompletableFuture<String> stop) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (leftover.destroys.get() == 0) { // asked to end, now waiting for it to exit
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }
        java.util.concurrent.CompletableFuture<String> status = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> m.running() + " " + m.awaitsAgent() + " " + m.otherSession() + " " + m.leftoverPid() + " "
                        + m.notRunning());
        assertThat(status.get(10, TimeUnit.SECONDS)).startsWith("false false Optional.empty " + leftover.pid())
                .contains("was left by an earlier session");
        // A start meanwhile refuses at once: the pid file still names the leftover.
        assertThatThrownBy(() -> java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                m.start(Duration.ofSeconds(60));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).get(10, TimeUnit.SECONDS)).hasMessageContaining("pid " + leftover.pid());
        assertThat(m.process()).isNull();
        assertThat(stop).isNotDone();
    }

    @Test
    void aStartNeverOverwritesThePidFileOfALiveLeftover() throws Exception {
        ServerManager m = manager(QuietServer.class);
        Process leftover = leftover(m);
        String before = Files.readString(m.pidFile());
        assertThatThrownBy(() -> m.start(Duration.ofSeconds(60))).isInstanceOf(ToolException.class)
                .hasMessageContaining("pid " + leftover.pid());
        assertThat(m.process()).isNull();
        assertThat(Files.readString(m.pidFile())).isEqualTo(before);
        assertThat(PidFile.resisting(m.pidFile())).isFalse();
    }

    @Test
    void nonAsciiTextSurvivesBothWaysUnderACLocale() throws Exception {
        ServerManager m = manager(EchoServer.class);
        // US-ASCII is the native encoding here, as Cp1252 is on Windows: only the flags keep the text whole (#466).
        m.childEnvironment(java.util.Map.of("LC_ALL", "C", "LANG", "C"));
        ServerManager.StartResult r = m.start(Duration.ofSeconds(120));
        assertThat(r.ready()).isTrue();
        assertThat(m.log().since(r.logMark())).contains("[12:00:01 INFO]: " + EchoServer.TEXT);
        m.console("say " + EchoServer.TEXT);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline && !m.log().since(r.logMark()).contains("got: say " + EchoServer.TEXT)
                && m.running()) {
            Thread.sleep(20);
        }
        assertThat(m.log().since(r.logMark())).contains("got: say " + EchoServer.TEXT);
    }

    /** A running process recorded in the manager's pid file, as an earlier core leaves it. */
    private Process leftover(ServerManager m) throws Exception {
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), QuietServer.class.getName()).start();
        leftovers.add(p);
        PidFile.write(m.pidFile(), p.toHandle());
        assertThat(m.leftoverPid()).isEqualTo(p.pid());
        return p;
    }

    /** Prints a long trace to stderr during start-up and exits 1. */
    public static final class CrashingServer {
        static final int LINES = 3000;
        static final String LAST = "Caused by: java.lang.IllegalStateException: the root cause";

        public static void main(String[] args) {
            System.err.println("java.lang.RuntimeException: mod loading failed");
            for (int i = 1; i < LINES; i++) {
                System.err.println("\tat com.example.Mod.frame" + i + "(Mod.java:" + i + ")");
            }
            System.err.println(LAST);
            System.err.flush();
            System.exit(1);
        }
    }

    /** Ready, then a crash with a trace long enough to still be in the pipe when the process is gone. */
    public static final class DoneThenCrashServer {
        static final String LAST = "\tat com.example.Mod.tick(Mod.java:500)";

        public static void main(String[] args) {
            System.out.println("[12:00:02 INFO]: Done (0.1s)! For help, type \"help\"");
            System.out.flush();
            System.out.println("[12:00:03 ERROR]: Encountered an unexpected exception");
            System.out.println("java.lang.IllegalStateException: tick failed");
            for (int i = 1; i <= 500; i++) {
                System.out.println("\tat com.example.Mod.tick(Mod.java:" + i + ")");
            }
            System.out.flush();
            System.exit(1);
        }
    }

    /** Prints non-ASCII text on System.out, as Minestom's slf4j-simple does, and echoes console lines. */
    public static final class EchoServer {
        static final String TEXT = "\u00e9 \u00a7 \u4f60 \uD83D\uDE00";

        public static void main(String[] args) throws Exception {
            System.out.println("[12:00:01 INFO]: " + TEXT);
            System.out.println("[12:00:02 INFO]: Done (0.1s)! For help, type \"help\"");
            System.out.flush();
            // Decodes the console as a JLine reader does: in the JVM's stdin encoding.
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in,
                    java.nio.charset.Charset.forName(System.getProperty("stdin.encoding"))));
            for (String line = in.readLine(); line != null && !line.equals("stop"); line = in.readLine()) {
                System.out.println("got: " + line);
                System.out.flush();
            }
        }
    }

    /** Ready at once, stops on "stop". */
    public static final class QuietServer {
        public static void main(String[] args) throws Exception {
            System.out.println("[12:00:02 INFO]: Done (0.1s)! For help, type \"help\"");
            System.out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            do {
                line = in.readLine();
            } while (line != null && !line.equals("stop"));
        }
    }
}
