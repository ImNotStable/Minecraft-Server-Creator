package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.PortHolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #577: which processes the bench may kill as left over from an attempt. */
class AttemptProcessesTest {

    @TempDir
    Path dir;

    @Test
    void aProcessNamingTheAttemptFolderOnItsCommandLineBelongsToIt() throws IOException {
        Path attempt = Files.createDirectories(dir.resolve("attempts/t/toolset-1"));
        Path retry = Files.createDirectories(dir.resolve("attempts/t/toolset-1-try2"));
        Path elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        // Started outside the folder, as a server through a Gradle daemon is: only its command line names it.
        Process jar = PortHolder.start(elsewhere, 0, elsewhere.resolve("a.port"),
                attempt.resolve("workspace/build/libs/game.jar").toString());
        Process other = PortHolder.start(elsewhere, 0, elsewhere.resolve("b.port"),
                retry.resolve("workspace/build/libs/game.jar").toString());
        try {
            assertThat(AttemptProcesses.in(attempt)).extracting(ProcessHandle::pid).containsExactly(jar.pid());
            assertThat(AttemptProcesses.in(dir.resolve("attempts"))).extracting(ProcessHandle::pid)
                    .contains(jar.pid(), other.pid());

            assertThat(AttemptProcesses.kill(attempt)).isEqualTo(1);
            assertThat(jar.isAlive()).isFalse();
            assertThat(other.isAlive()).as("toolset-1 is not toolset-1-try2").isTrue();
        } finally {
            jar.destroyForcibly();
            other.destroyForcibly();
        }
    }

    @Test
    void aProcessWorkingInTheAttemptFolderBelongsToItWhereTheSystemShowsIt() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(Path.of("/proc/self/cwd")), "the working folder shows only on Linux");
        Path workspace = Files.createDirectories(dir.resolve("attempts/t/baseline-1/workspace"));
        Path ports = Files.createDirectories(dir.resolve("ports"));
        // `java -jar build/libs/game.jar`, detached: a relative path, so only its working folder names the attempt.
        Process server = PortHolder.start(workspace, 0, ports.resolve("a.port"));
        try {
            assertThat(AttemptProcesses.in(dir.resolve("attempts/t/baseline-1"))).extracting(ProcessHandle::pid)
                    .containsExactly(server.pid());
            assertThat(AttemptProcesses.in(dir.resolve("attempts/t/baseline-2"))).isEmpty();
            assertThat(AttemptProcesses.in(ports)).isEmpty();
        } finally {
            server.destroyForcibly();
        }
    }

    @Test
    void neverTheBenchItself() {
        // The test JVM runs in the module folder, and its class path names it.
        Path module = Path.of("").toAbsolutePath();
        assertThat(AttemptProcesses.in(module)).extracting(ProcessHandle::pid)
                .doesNotContain(ProcessHandle.current().pid());
    }
}
