package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.fixture.SleepMain;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two cores on one workspace (#482, ADR 0037): two {@link ServerManager}s, one standing for another core through an
 * injected identity. A server belongs to the core that started it while that core lives; once it is gone, the server
 * is a leftover as before.
 */
class ServerOwnershipTest {
    private static final String REFUSAL = "belongs to another session that is still running";

    @TempDir
    Path workspace;
    private final List<AutoCloseable> closing = new ArrayList<>();
    private final List<Process> processes = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closing.reversed()) {
            c.close();
        }
        processes.forEach(Process::destroyForcibly);
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** A core's server manager on the shared workspace, its servers {@link ServerManagerTest.QuietServer}s. */
    private ServerManager core() throws Exception {
        AgentHub hub = new AgentHub(0, "token");
        ServerManager m = new ServerManager(CoreConfig.forWorkspace(workspace).withServerCommand(List.of(java(), "-cp",
                System.getProperty("java.class.path"), ServerManagerTest.QuietServer.class.getName())), hub);
        closing.add(hub);
        closing.add(m);
        return m;
    }

    /** A live process of {@code main}: another core, or a server. */
    private Process spawn(Class<?> main) throws Exception {
        Process p = new ProcessBuilder(java(), "-cp", System.getProperty("java.class.path"), main.getName(), "3600")
                .start();
        processes.add(p);
        return p;
    }

    @Test
    void aSecondCoreRefusesWhileTheFirstLivesAndTakesTheLeftoverOnceItIsKilled() throws Exception {
        Process otherCore = spawn(SleepMain.class);
        ServerManager first = core();
        first.self(PidFile.Identity.of(otherCore.toHandle()));
        ServerManager second = core();
        assertThat(first.start(Duration.ofSeconds(60)).ready()).isTrue();
        long serverPid = first.process().pid();
        String file = Files.readString(first.pidFile());
        assertThat(file).isEqualTo(serverPid + " " + PidFile.Identity.of(first.process().toHandle()).startedAt() + " "
                + otherCore.pid() + " " + PidFile.Identity.of(otherCore.toHandle()).startedAt() + "\n");

        assertThat(second.otherSession()).contains(new ServerManager.OtherSession(serverPid, otherCore.pid()));
        assertThat(second.leftoverPid()).isEqualTo(-1);
        for (ThrowingCall call : List.<ThrowingCall>of(second::stopLeftover, () -> second.stopLeftover(true),
                () -> second.start(Duration.ofSeconds(60)), second::checkNoOtherSession)) {
            assertThatThrownBy(call::run).isInstanceOf(ToolException.class).hasMessageContaining(REFUSAL)
                    .hasMessageContaining("(pid " + serverPid + ")").hasMessageContaining("MSC core pid " + otherCore.pid())
                    .hasMessageContaining("use that session, or end it").hasMessageContaining("Nothing was changed")
                    .hasMessageNotContaining("\n");
        }
        assertThat(first.running()).isTrue();
        assertThat(second.running()).isFalse();
        assertThat(Files.readString(first.pidFile())).isEqualTo(file);

        // The first core is killed: its server is a leftover, as ADR 0013 point 8 intends.
        otherCore.destroyForcibly().waitFor();
        assertThat(second.otherSession()).isEmpty();
        assertThat(second.leftoverPid()).isEqualTo(serverPid);
        assertThat(second.stopLeftover()).contains("stopped a server left running by an earlier session (pid " + serverPid);
        assertThat(first.running()).isFalse();
        assertThat(second.start(Duration.ofSeconds(60)).ready()).isTrue();
        assertThat(PidFile.owner(second.pidFile())).contains(PidFile.Identity.current());
    }

    @Test
    void theFirstCoreRefusesTheSecondCoresServerTheSameWay() throws Exception {
        // The trigger of #482 the other way round: the server of this JVM's core, seen from another core.
        ServerManager first = core();
        ServerManager second = core();
        second.self(new PidFile.Identity(ProcessHandle.current().pid() + 1, 1));
        assertThat(first.start(Duration.ofSeconds(60)).ready()).isTrue();
        long serverPid = first.process().pid();
        assertThatThrownBy(second::stopLeftover).hasMessageContaining(REFUSAL)
                .hasMessageContaining("MSC core pid " + ProcessHandle.current().pid());
        assertThat(second.leftoverPid()).isEqualTo(-1);
        assertThat(first.running()).isTrue();
        // Its own manager sees its own server as running, not as another session's.
        assertThat(first.otherSession()).isEmpty();
        assertThat(first.stop(Duration.ofSeconds(30))).isTrue();
        assertThat(first.pidFile()).doesNotExist();
        assertThat(second.otherSession()).isEmpty();
        assertThat(serverPid).isPositive();
    }

    @Test
    void anOwnerThatIsNotAliveOrUnknownLeavesALeftoverAsBefore() throws Exception {
        Process server = spawn(SleepMain.class);
        Process deadCore = spawn(SleepMain.class);
        PidFile.Identity dead = PidFile.Identity.of(deadCore.toHandle());
        deadCore.destroyForcibly().waitFor();
        PidFile.Identity me = PidFile.Identity.current();
        ServerManager m = core();
        m.self(new PidFile.Identity(1_900_000_001L, 1));
        // Not alive by pid and start time: a reused core pid (this JVM's pid, another start), an unknown start
        // time, a core that ended. Never by pid alone.
        for (PidFile.Identity owner : List.of(new PidFile.Identity(me.pid(), me.startedAt() + 1),
                new PidFile.Identity(me.pid(), 0), dead)) {
            PidFile.write(m.pidFile(), server.toHandle(), owner);
            assertThat(m.otherSession()).as("%s", owner).isEmpty();
            assertThat(m.leftoverPid()).as("%s", owner).isEqualTo(server.pid());
        }
        // A pid file of a core before ADR 0037: no owner, a leftover.
        PidFile.write(m.pidFile(), server.toHandle());
        assertThat(Files.readString(m.pidFile())).matches(server.pid() + " \\d+\n");
        assertThat(PidFile.owner(m.pidFile())).isEmpty();
        assertThat(m.otherSession()).isEmpty();
        assertThat(m.leftoverPid()).isEqualTo(server.pid());
        // A file naming this very core: its own, a leftover it lost track of.
        m.self(me);
        PidFile.write(m.pidFile(), server.toHandle(), me);
        assertThat(m.otherSession()).isEmpty();
        assertThat(m.leftoverPid()).isEqualTo(server.pid());
        assertThat(server.isAlive()).as("only looked at").isTrue();
    }

    @Test
    void theResistingMarkKeepsTheOwner() throws Exception {
        Process server = spawn(SleepMain.class);
        Path file = Files.createDirectories(workspace.resolve(".msc")).resolve("server.pid");
        PidFile.write(file, server.toHandle(), PidFile.Identity.current());
        String written = Files.readString(file);
        PidFile.markResisting(file);
        PidFile.markResisting(file);
        assertThat(Files.readString(file)).isEqualTo(written.strip() + " " + PidFile.RESISTING + "\n");
        assertThat(PidFile.resisting(file)).isTrue();
        assertThat(PidFile.owner(file)).contains(PidFile.Identity.current());
        assertThat(PidFile.peek(file).map(ProcessHandle::pid)).contains(server.pid());
    }

    @Test
    void aStopLeavesAPidFileAnotherCoreWroteMeanwhile() throws Exception {
        ServerManager m = core();
        assertThat(m.start(Duration.ofSeconds(60)).ready()).isTrue();
        Process first = m.process();
        assertThat(m.stop(Duration.ofSeconds(30))).isTrue();
        // Another core started its server after this one exited, before this stop cleaned up.
        Process other = spawn(SleepMain.class);
        PidFile.write(m.pidFile(), other.toHandle(), new PidFile.Identity(1_900_000_002L, 1));
        m.released(first);
        assertThat(PidFile.peek(m.pidFile()).map(ProcessHandle::pid)).contains(other.pid());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
