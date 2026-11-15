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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    /**
     * A core's server manager on the shared workspace; its server locks the world, as a real one does. Not a port
     * (#580): a free port handed to the server is free for every other process too until the server binds it, and
     * any of them could take it first.
     */
    private ServerManager core() throws Exception {
        AgentHub hub = new AgentHub(0, "token");
        ServerManager m = new ServerManager(CoreConfig.forWorkspace(workspace).withServerCommand(List.of(java(), "-cp",
                System.getProperty("java.class.path"), WorldServer.class.getName())), hub);
        closing.add(hub);
        closing.add(m);
        return m;
    }

    /** Asserts that {@code r} is ready, saying why not with the result and the server's log. */
    private static void assertReady(ServerManager m, ServerManager.StartResult r) {
        assertThat(r.ready()).as("%s, log %s", r, m.log().all()).isTrue();
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
        ServerManager other = core();
        other.self(PidFile.Identity.of(otherCore.toHandle()));
        ServerManager mine = core();
        List<ServerManager> cores = List.of(other, mine);

        List<Object> outcomes = race(List.of(() -> other.start(Duration.ofSeconds(120)),
                () -> mine.start(Duration.ofSeconds(120))));

        List<Integer> won = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            if (outcomes.get(i) instanceof ServerManager.StartResult r) {
                assertReady(cores.get(i), r);
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
        ServerManager other = core();
        other.self(PidFile.Identity.of(otherCore.toHandle()));
        ServerManager mine = core();
        assertReady(other, other.start(Duration.ofSeconds(120)));

        List<Object> outcomes = race(List.of(() -> other.stop(Duration.ofSeconds(60)),
                () -> mine.start(Duration.ofSeconds(120))));

        assertThat(outcomes.getFirst()).isEqualTo(true);
        assertThat(other.running()).isFalse();
        if (outcomes.get(1) instanceof ServerManager.StartResult r) {
            // Started once the other server had exited: its stop must not delete this record.
            assertReady(mine, r);
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
        ServerManager m = core();
        ServerLockAccess.refuseQuickly(m);
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

    /**
     * A leftover that resisted before, so stopLeftover kills it once more; {@code kill} runs while no section is
     * held, so another core can take the lock there and keep it past the record's update.
     */
    private ServerManager leftoverStoppedWhileTheLockIsTaken(Process server, boolean kills) throws Exception {
        ServerManager m = core();
        ServerLockAccess.refuseQuickly(m);
        PidFile.write(m.pidFile(), server.toHandle(), new PidFile.Identity(1_900_000_003L, 1));
        PidFile.markResisting(m.pidFile());
        Path lock = workspace.resolve(".msc/server.lock");
        m.leftovers(new ServerManager.Leftovers(PidFile::peek, h -> {
            try {
                processes.add(LockHoldMain.hold(lock));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            if (kills) {
                h.destroyForcibly();
            }
        }, Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30),
                kills ? Duration.ofSeconds(60) : ServerLockAccess.HELD));
        return m;
    }

    @Test
    void aLeftoverStoppedWhileAnotherCoreTakesTheLockIsReportedStoppedNotUnchanged() throws Exception {
        Process server = new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"),
                SleepMain.class.getName(), "3600").start();
        processes.add(server);
        ServerManager m = leftoverStoppedWhileTheLockIsTaken(server, true);
        String file = Files.readString(m.pidFile());

        assertThat(m.stopLeftover()).contains("stopped a server left running by an earlier session (pid "
                + server.pid()).contains("was not cleared").doesNotContain("Nothing was changed");
        assertThat(server.isAlive()).isFalse();
        assertThat(Files.readString(m.pidFile())).as("left as it was").isEqualTo(file);
    }

    @Test
    void aLeftoverThatSurvivesWhileAnotherCoreTakesTheLockIsReportedStillRunning() throws Exception {
        Process server = new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"),
                SleepMain.class.getName(), "3600").start();
        processes.add(server);
        ServerManager m = leftoverStoppedWhileTheLockIsTaken(server, false);

        assertThatThrownBy(m::stopLeftover).isInstanceOf(ToolException.class)
                .hasMessageContaining("(pid " + server.pid() + ") could not be stopped").hasMessageNotContaining(BUSY);
        assertThat(server.isAlive()).isTrue();
    }

    @Test
    void aCheckWaitsForAnotherManagerOfThisJvmWhoseLockFileDoesNotExistYet() throws Exception {
        Path dataDir = Files.createDirectories(workspace.resolve(".msc"));
        ServerPidFile first = new ServerPidFile(dataDir);
        ServerPidFile second = new ServerPidFile(dataDir);
        second.waitLimit(ServerLockAccess.HELD);

        String outcome = first.locked(held -> {
            // The first section on a workspace, between its in-process lock and the file's creation.
            Files.delete(first.lockFile());
            try {
                threads.submit(() -> {
                    second.awaitIdle();
                    return null;
                }).get();
                return "the check went ahead";
            } catch (ExecutionException e) {
                return e.getCause().getMessage();
            }
        });

        assertThat(outcome).contains(BUSY);
    }

    /**
     * A server that locks its world's {@code session.lock} as a real one does, so a second one in the same server
     * folder dies on it, then waits for "stop". The lock is the test's own: nothing outside it can take it, and the OS
     * releases it when the server exits.
     */
    public static final class WorldServer {
        public static void main(String[] args) throws Exception {
            Path file = Path.of("world", "session.lock");
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    System.out.println("[12:00:01 ERROR]: Failed to start the minecraft server: " + file
                            + " is locked by another server");
                    System.out.flush();
                    System.exit(1);
                }
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
