package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.process.rcon.FakeRconServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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
        Files.writeString(m.serverDir().resolve("server.properties"),
                "enable-rcon=true\nrcon.password=pw\n" + line + "\n");
        assertThat(m.stopLeftover()).contains("pid " + leftover.pid(), "killed");
        assertThat(leftover.isAlive()).isFalse();
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
