package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.server.ProjectBuilder;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildWarmupTest {
    @TempDir
    Path dir;

    @Test
    void devCycleWaitsForTheWarmupOnceThenNeverAgain() throws Exception {
        Path project = Files.createDirectories(dir.resolve("mod"));
        Path marker = dir.resolve("built");
        // A build command that takes a moment and records its tasks, standing in for Gradle.
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path script = dir.resolve("Build.java");
        Files.writeString(script, """
                class Build {
                    public static void main(String[] a) throws Exception {
                        Thread.sleep(300);
                        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("marker")), String.join(" ", a));
                    }
                }
                """);
        CoreConfig config = CoreConfig.forWorkspace(dir)
                .withBuildCommand(List.of(java, "-Dmarker=" + marker, script.toString()));
        BuildWarmup warmup = new BuildWarmup(new ProjectBuilder(config), new BackgroundWork());

        assertThat(warmup.await(project)).isEqualTo(-1);
        warmup.start(project.resolve("."));
        assertThat(warmup.await(project)).isGreaterThanOrEqualTo(0);
        assertThat(marker).hasContent("mscJar check");
        assertThat(warmup.await(project)).isEqualTo(-1);
    }

    @Test
    void closingTheWorkStopsTheWarmupAndAnInterruptedWaiterLeavesItKnown() throws Exception {
        Path project = Files.createDirectories(dir.resolve("mod"));
        Files.writeString(project.resolve("version.txt"), "1.0");
        Files.writeString(project.resolve("slow-check.flag"), "");
        CoreConfig config = CoreConfig.forWorkspace(dir).withBuildCommand(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FakeBuildMain.class.getName()));
        BackgroundWork work = new BackgroundWork();
        BuildWarmup warmup = new BuildWarmup(new ProjectBuilder(config), work);
        warmup.start(project);
        Path started = project.resolve("check-started");
        while (!Files.exists(started)) {
            Thread.sleep(20);
        }
        ProcessHandle build = ProcessHandle.of(Long.parseLong(Files.readString(started).strip())).orElseThrow();

        // A dev_cycle interrupted while it waits (the session ends) must not make the next one skip the wait.
        CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                warmup.await(project);
                interrupted.complete(false);
            } catch (InterruptedException e) {
                interrupted.complete(true);
            }
        });
        Thread.sleep(200);
        waiter.interrupt();
        assertThat(interrupted.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(build.isAlive()).isTrue();

        work.close();
        build.onExit().get(3, TimeUnit.SECONDS);
        assertThat(warmup.await(project)).isGreaterThanOrEqualTo(0);
        assertThatThrownBy(() -> warmup.start(project)).isInstanceOf(ToolException.class).hasMessageContaining("closed");
    }
}
