package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.server.ProjectBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        BuildWarmup warmup = new BuildWarmup(new ProjectBuilder(config));

        assertThat(warmup.await(project)).isEqualTo(-1);
        warmup.start(project.resolve("."));
        assertThat(warmup.await(project)).isGreaterThanOrEqualTo(0);
        assertThat(marker).hasContent("mscJar check");
        assertThat(warmup.await(project)).isEqualTo(-1);
    }
}
