package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerManagerTest {
    @TempDir
    Path workspace;
    private AgentHub hub;
    private ServerManager server;

    @AfterEach
    void close() throws Exception {
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
