package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.fixture.LockHoldMain;
import io.github.imnotstable.msc.core.fixture.SleepMain;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every read-decide-write of {@code .msc/server.pid} is one critical section across cores (#490, ADR 0037 point 3):
 * two cores starting at the same moment launch one server, and the pid file names it.
 */
class ServerPidFileLockTest {
    private static final String OTHER_SESSION = "belongs to another session that is still running";
    private static final String BUSY = "is starting or stopping the server right now";

    /** Another session's core, still running: one of the two managers stands for it. */
    private static Process otherCore;

    @TempDir
    Path workspace;
    private final List<AutoCloseable> closing = new ArrayList<>();
    private final List<Process> processes = new ArrayList<>();
    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    @BeforeAll
    static void startOtherCore() throws Exception {
        otherCore = new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"), SleepMain.class.getName(),
                "3600").start();
    }

    @AfterAll
    static void endOtherCore() {
        otherCore.destroyForcibly();
    }

    @AfterEach
    void close() throws Exception {
        threads.shutdownNow();
        for (AutoCloseable c : closing.reversed()) {
            c.close();
        }
        processes.forEach(Process::destroyForcibly);
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** A core's server manager on the shared workspace; its server binds {@code port}, as a real one does. */
    private ServerManager core(int port, Duration lockWait) throws Exception {
        AgentHub hub = new AgentHub(0, "token");
        ServerManager m = new ServerManager(CoreConfig.forWorkspace(workspace).withServerCommand(List.of(java(), "-cp",
                System.getProperty("java.class.path"), PortServer.class.getName(), Integer.toString(port))), hub,
                lockWait);
        closing.add(hub);
        closing.add(m);
        return m;
    }

    private ServerManager core(int port) throws Exception {
        return core(port, ServerPidFile.WAIT);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** Runs each call on its own thread, all released by one latch, and returns what each returned or threw. */
    private List<Object> race(List<Callable<?>> calls) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<?> call : calls) {
            futures.add(threads.submit(() -> {
                go.await();
                return call.call();
            }));
        }
        go.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<?> f : futures) {
            try {
                outcomes.add(f.get());
            } catch (ExecutionException e) {
                outcomes.add(e.getCause());
            }
        }
        return outcomes;
    }

    @RepeatedTest(50)
    void twoCoresStartingAtOnceLaunchOneServerAndThePidFileNamesIt() throws Exception {
        int port = freePort();
        ServerManager other = core(port);
        other.self(PidFile.Identity.of(otherCore.toHandle()));
        ServerManager mine = core(port);
        List<ServerManager> cores = List.of(other, mine);

        List<Object> outcomes = race(List.of(() -> other.start(Duration.ofSeconds(120)),
                () -> mine.start(Duration.ofSeconds(120))));

        List<Integer> won = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            if (outcomes.get(i) instanceof ServerManager.StartResult r) {
                assertThat(r.ready()).as("the one server started").isTrue();
                won.add(i);
            } else {
                assertThat(outcomes.get(i)).isInstanceOf(ToolException.class);
                assertThat(((Throwable) outcomes.get(i)).getMessage()).contains(OTHER_SESSION).doesNotContain("\n");
                assertThat(cores.get(i).process()).as("the refused core launched nothing").isNull();
            }
        }
        assertThat(won).as("exactly one launches").hasSize(1);
        ServerManager winner = cores.get(won.getFirst());
        assertThat(winner.running()).isTrue();
        Path file = winner.pidFile();
        assertThat(PidFile.peek(file).map(ProcessHandle::pid)).as("the pid file names the live server")
                .contains(winner.process().pid());
        assertThat(PidFile.owner(file)).contains(winner == other ? PidFile.Identity.of(otherCore.toHandle())
                : PidFile.Identity.current());
    }

    @RepeatedTest(20)
    void aStopRacingAnotherCoresStartLeavesTheRecordOfWhateverRuns() throws Exception {
        int port = freePort();
        ServerManager other = core(port);
        other.self(PidFile.Identity.of(otherCore.toHandle()));
        ServerManager mine = core(port);
        assertThat(other.start(Duration.ofSeconds(120)).ready()).isTrue();

        List<Object> outcomes = race(List.of(() -> other.stop(Duration.ofSeconds(60)),
                () -> mine.start(Duration.ofSeconds(120))));

        assertThat(outcomes.getFirst()).isEqualTo(true);
        assertThat(other.running()).isFalse();
        if (outcomes.get(1) instanceof ServerManager.StartResult r) {
            // Started once the other server had exited: its stop must not delete this record.
            assertThat(r.ready()).isTrue();
            assertThat(PidFile.peek(mine.pidFile()).map(ProcessHandle::pid)).contains(mine.process().pid());
            assertThat(PidFile.owner(mine.pidFile())).contains(PidFile.Identity.current());
        } else {
            assertThat(outcomes.get(1)).isInstanceOf(ToolException.class);
            assertThat(((Throwable) outcomes.get(1)).getMessage()).contains(OTHER_SESSION);
            assertThat(mine.process()).isNull();
            assertThat(mine.pidFile()).doesNotExist();
        }
    }

    @Test
    void anotherProcessHoldingTheLockMakesEveryChangeRefuseAndNothingChanges() throws Exception {
        ServerManager m = core(freePort(), Duration.ofSeconds(1));
        Process holder = LockHoldMain.hold(workspace.resolve(".msc/server.lock"));
        processes.add(holder);
        Process server = new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"),
                SleepMain.class.getName(), "3600").start();
        processes.add(server);
        // A leftover whose core is gone: stopLeftover would kill it, but only once it has claimed it.
        PidFile.write(m.pidFile(), server.toHandle(), new PidFile.Identity(1_900_000_003L, 1));
        String file = Files.readString(m.pidFile());

        assertThatThrownBy(() -> m.start(Duration.ofSeconds(60))).isInstanceOf(ToolException.class)
                .hasMessageContaining(BUSY).hasMessageContaining("Nothing was changed").hasMessageNotContaining("\n");
        assertThatThrownBy(m::stopLeftover).hasMessageContaining(BUSY);
        assertThatThrownBy(m::checkNoOtherSession).hasMessageContaining(BUSY);
        assertThat(m.process()).isNull();
        assertThat(server.isAlive()).isTrue();
        assertThat(Files.readString(m.pidFile())).isEqualTo(file);

        // The holder ends (a core killed mid-start leaves the file behind): the OS released the lock.
        holder.destroyForcibly().waitFor();
        assertThat(workspace.resolve(".msc/server.lock")).exists();
        m.leftovers(new ServerManager.Leftovers(PidFile::peek, ProcessRunner::destroyTree, Duration.ofSeconds(1),
                Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30)));
        assertThat(m.stopLeftover()).contains("pid " + server.pid());
        assertThat(m.pidFile()).doesNotExist();
    }

    /** A server that binds its port as a real one does, so a second one dies on it, then waits for "stop". */
    public static final class PortServer {
        public static void main(String[] args) throws Exception {
            try (ServerSocket socket = new ServerSocket(Integer.parseInt(args[0]))) {
                System.out.println("[12:00:01 INFO]: Listening on port " + socket.getLocalPort());
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
}
