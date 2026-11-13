package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.fixture.TreeMain;
import io.github.imnotstable.msc.core.server.ProcessRunner;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ending the session or switching the workspace while tool calls, and the builds they started, still run. */
class SessionEndTest {
    @TempDir
    Path workspace;
    private CoreHarness h;

    private static List<String> java(Class<?> main, String... jvmArgs) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.addAll(List.of(jvmArgs));
        command.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName()));
        return command;
    }

    @BeforeEach
    void start() throws Exception {
        h = new CoreHarness(workspace, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class, "-Dfake.slowCheck=true")));
        h.core.lockWait(Duration.ofMillis(300));
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    /** The pid the slow fake build wrote once its check started. */
    private static ProcessHandle buildStarted(Path project) throws Exception {
        Path started = project.resolve("check-started");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.exists(started)) {
            assertThat(System.nanoTime()).as("the build started").isLessThan(deadline);
            Thread.sleep(20);
        }
        return ProcessHandle.of(Long.parseLong(Files.readString(started).strip())).orElseThrow();
    }

    private static void assertEnded(ProcessHandle build) throws Exception {
        // The close waited for the kill, so the build is gone (or going) when it returns, not after its 60 s.
        build.onExit().get(3, TimeUnit.SECONDS);
        assertThat(build.isAlive()).isFalse();
    }

    @Test
    void closingDuringDevCycleTestWaitStopsTheTests() throws Exception {
        Path project = Files.createDirectories(workspace.resolve("demo"));
        Files.writeString(project.resolve("version.txt"), "1.0");
        CompletableFuture<String> cycle = CompletableFuture.supplyAsync(
                () -> h.call("dev_cycle", "project", "demo").text());
        ProcessHandle check = buildStarted(project);
        // The restart finishes long before the 60 s check: dev_cycle then only waits for the tests.
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (h.core.hub().readyCount() == 0) {
            assertThat(System.nanoTime()).as("server ready").isLessThan(deadline);
            Thread.sleep(20);
        }
        Thread.sleep(1000);
        h.close();
        assertEnded(check);
        assertThat(cycle.get(10, TimeUnit.SECONDS)).startsWith("error: ");
    }

    private ProcessHandle scaffoldWithWarmup() throws Exception {
        assertThat(h.ok("project_scaffold", "name", "Mod", "dir", "mod", "template", "forge-mod"))
                .contains("first build started in the background");
        return buildStarted(workspace.resolve("mod"));
    }

    @Test
    void closingDuringAWarmupStopsIt() throws Exception {
        ProcessHandle warmup = scaffoldWithWarmup();
        h.close();
        assertEnded(warmup);
    }

    @Test
    void switchingAwayDuringAWarmupStopsIt() throws Exception {
        ProcessHandle warmup = scaffoldWithWarmup();
        h.ok("msc_workspace", "path", "other", "create", true);
        assertEnded(warmup);
    }

    /** A tool that runs until {@code release}; {@code stubborn} ones keep running when interrupted. */
    private void blockingTool(String name, CountDownLatch release, boolean stubborn) {
        h.core.addBenchTool(new Tool(name, "test", Schema.of().build(), args -> {
            while (true) {
                try {
                    release.await();
                    return name + " done";
                } catch (InterruptedException e) {
                    if (!stubborn) {
                        throw e;
                    }
                }
            }
        }));
    }

    private CompletableFuture<String> callAsync(String tool, Object... pairs) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> result.complete(h.call(tool, pairs).text()));
        Thread.sleep(300);
        return result;
    }

    @Test
    void aCallThatIgnoresTheInterruptHoldsUpNeitherTheCloseNorTheServerStop() throws Exception {
        h.ok("server_start", "timeoutSeconds", 30);
        CountDownLatch release = new CountDownLatch(1);
        blockingTool("stubborn", release, true);
        h.core.cleanupWait(Duration.ofMillis(300));
        CompletableFuture<String> call = callAsync("stubborn");
        long started = System.nanoTime();
        h.close();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(h.core.server().running()).isFalse();
        assertThat(call).isNotDone();
        release.countDown();
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("stubborn done");
        assertThat(h.error("msc_status")).isEqualTo("error: the session has ended; msc_status did not run");
    }

    @Test
    void closeReturnsOnlyOnceAnInterruptedCallHasCleanedUp() throws Exception {
        // The JVM exits right after close: a call still cleaning up then would leave its processes behind.
        AtomicBoolean cleanedUp = new AtomicBoolean();
        h.core.addBenchTool(new Tool("slow-cleanup", "test", Schema.of().build(), args -> {
            try {
                new CountDownLatch(1).await();
                return "never";
            } catch (InterruptedException e) {
                // Cleaning up takes a moment, like ProcessRunner killing a process tree.
                Thread.sleep(150);
                cleanedUp.set(true);
                throw e;
            }
        }));
        callAsync("slow-cleanup");
        h.close();
        assertThat(cleanedUp).isTrue();
    }

    @Test
    void closingDuringABuildOnTheCallThreadKillsItsProcessTreeBeforeReturning() throws Exception {
        // dev_cycle's jar build and server_setup's installer run ProcessRunner on the call thread itself (#296).
        AtomicBoolean treeKilled = new AtomicBoolean();
        h.core.addBenchTool(new Tool("call-build", "test", Schema.of().build(), args -> {
            try {
                return ProcessRunner.run(java(TreeMain.class), workspace, Duration.ofSeconds(60)).output();
            } catch (InterruptedException e) {
                // ProcessRunner throws only after it sent the forced kill to the whole tree.
                treeKilled.set(true);
                throw e;
            }
        }));
        CompletableFuture<String> call = callAsync("call-build");
        Path pids = workspace.resolve("tree-pids");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.exists(pids)) {
            assertThat(System.nanoTime()).as("the build started").isLessThan(deadline);
            Thread.sleep(20);
        }
        List<ProcessHandle> tree = Arrays.stream(Files.readString(pids).strip().split(" "))
                .map(pid -> ProcessHandle.of(Long.parseLong(pid)).orElseThrow()).toList();
        h.close();
        // No race: the call sets this before it ends, and close returns only after the call ended.
        assertThat(treeKilled).isTrue();
        // Killed, but an orphaned child is listed until init reaps it, which in a container can take seconds.
        for (ProcessHandle p : tree) {
            p.onExit().get(30, TimeUnit.SECONDS);
        }
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("error: call-build was stopped: the session ended while it ran");
    }

    @Test
    void aCallThatEndsDuringTheWaitLetsTheSwitchThrough() throws Exception {
        h.core.lockWait(Duration.ofSeconds(10));
        CountDownLatch release = new CountDownLatch(1);
        blockingTool("block", release, false);
        CompletableFuture<String> call = callAsync("block");
        CompletableFuture<String> sw = callAsync("msc_workspace", "path", "other", "create", true);
        release.countDown();
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("block done");
        assertThat(sw.get(10, TimeUnit.SECONDS)).startsWith("workspace: " + workspace.resolve("other").toRealPath());
    }

    @Test
    void aSecondSwitchToTheSameFolderDuringTheWaitFindsItDone() throws Exception {
        h.core.lockWait(Duration.ofSeconds(10));
        CountDownLatch release = new CountDownLatch(1);
        blockingTool("block", release, false);
        callAsync("block");
        Path other = Files.createDirectories(workspace.resolve("other")).toRealPath();
        CompletableFuture<String> first = callAsync("msc_workspace", "path", "other");
        CompletableFuture<String> second = callAsync("msc_workspace", "path", "other");
        release.countDown();
        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("workspace is already " + other,
                        "workspace: " + other + "\nserver folder: " + other.resolve("run") + "; platform: paper");
    }

    @Test
    void aCloseWhileASwitchWaitsEndsTheCallAndTheSwitchDoesNotHappen() throws Exception {
        h.core.lockWait(Duration.ofSeconds(10));
        blockingTool("block", new CountDownLatch(1), false);
        CompletableFuture<String> call = callAsync("block");
        CompletableFuture<String> sw = callAsync("msc_workspace", "path", "other", "create", true);
        h.core.lockWait(Duration.ofMillis(300));
        h.close();
        // What the interrupted caller receives: an error that says why, not a bare "interrupted".
        assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("error: block was stopped: the session ended while it ran");
        assertThat(sw.get(10, TimeUnit.SECONDS)).isEqualTo("error: did not switch: the session has ended");
        assertThat(h.core.config().workspace()).isEqualTo(workspace);
    }
}
