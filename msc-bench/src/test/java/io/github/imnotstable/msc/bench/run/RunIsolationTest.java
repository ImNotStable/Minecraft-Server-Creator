package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunIsolationTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"CLAUDE.md", "CLAUDE.local.md", ".claude/CLAUDE.md", ".git/HEAD"})
    void aMarkerInAnyFolderAboveTheRunFolderIsALeak(String marker) throws IOException {
        Path project = dir.resolve("project");
        Path file = project.resolve(marker);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        Path runs = project.resolve("msc-bench/runs");

        List<Path> leaks = RunIsolation.leaks(runs, dir.resolve("home"));

        String expected = marker.startsWith(".git/") ? ".git" : marker;
        assertThat(leaks).containsExactly(project.toRealPath().resolve(expected));
        assertThat(catchThrowable(() -> RunIsolation.requireIsolated(runs, dir.resolve("home"))))
                .isInstanceOf(IOException.class).hasMessageContaining("--out");
        assertThat(runs).as("nothing is created for a refused run folder").doesNotExist();
    }

    @Test
    void theDefaultRunFolderIsIsolatedAndTheUsersOwnMemoryDoesNotCount() throws IOException {
        Path home = dir.resolve("home");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/CLAUDE.md"), "user memory, left out by --setting-sources");

        assertThat(RunIsolation.leaks(RunIsolation.defaultRunsDir(home), home)).isEmpty();
    }

    @Test
    void theOldDefaultInsideThisRepositoryIsRefused() {
        // Tests run in msc-bench/, where the old default runs/ put every attempt under the repository's CLAUDE.md.
        assertThat(RunIsolation.leaks(Path.of("runs"), dir))
                .anySatisfy(p -> assertThat(p.getFileName()).hasToString("CLAUDE.md"))
                .anySatisfy(p -> assertThat(p.getFileName()).hasToString(".git"));
    }
}
