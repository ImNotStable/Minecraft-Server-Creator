package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
}
