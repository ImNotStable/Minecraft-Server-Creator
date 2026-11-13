package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        assertThat(catchThrowable(() -> RunIsolation.requireIsolated(runs, dir.resolve("home"), dir.resolve("tmp"))))
                .isInstanceOf(IOException.class).hasMessageContaining("--out")
                .hasMessageContaining(RunIsolation.homeRunsDir(dir.resolve("home")).getFileName().toString());
        assertThat(runs).as("nothing is created for a refused run folder").doesNotExist();
    }

    @Test
    void theDefaultRunFolderIsIsolatedAndTheUsersOwnMemoryDoesNotCount() throws IOException {
        Path home = dir.resolve("home");
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude/CLAUDE.md"), "user memory, left out by --setting-sources");

        assertThat(RunIsolation.defaultRunsDir(home, dir.resolve("tmp")))
                .isEqualTo(home.toRealPath().resolve(".msc/bench-runs"));
    }

    @Test
    void theShownDefaultIsTheDefault() {
        Path home = dir.resolve("home");
        String relative = home.relativize(RunIsolation.homeRunsDir(home)).toString().replace('\\', '/');

        assertThat(RunIsolation.DEFAULT_RUNS_SHOWN).isEqualTo("~/" + relative);
    }

    @ParameterizedTest
    @ValueSource(strings = {".git/HEAD", "CLAUDE.md"})
    void aHomeFolderInsideAProjectFallsBackToTheTempFolderAndNeverSuggestsTheRefusedDefault(String marker)
            throws IOException {
        // A dotfiles repository at ~ (or a ~/CLAUDE.md) put ~/.msc/bench-runs inside a project: run without --out
        // always failed, and the error suggested that same folder.
        Path home = dir.resolve("home");
        Files.createDirectories(home.resolve(marker).getParent());
        Files.writeString(home.resolve(marker), "x");
        Path temp = Files.createDirectories(dir.resolve("tmp"));

        Path chosen = RunIsolation.defaultRunsDir(home, temp);

        assertThat(chosen).isEqualTo(temp.toRealPath().resolve(RunIsolation.TEMP_RUNS_NAME));
        assertThat(RunIsolation.leaks(chosen, home)).isEmpty();
        Throwable refused = catchThrowable(() -> RunIsolation.requireIsolated(RunIsolation.homeRunsDir(home), home, temp));
        assertThat(refused).isInstanceOf(IOException.class).hasMessageContaining("for example " + chosen);
        assertThat(refused.getMessage().split("for example")[1]).doesNotContain(".msc");
    }

    @Test
    void whenNoDefaultIsIsolatedTheErrorSuggestsNoRefusedFolder() throws IOException {
        Path home = dir.resolve("home");
        Files.createDirectories(home.resolve(".git"));
        Path temp = Files.createDirectories(home.resolve("tmp"));

        Throwable noDefault = catchThrowable(() -> RunIsolation.defaultRunsDir(home, temp));
        Throwable refused = catchThrowable(() -> RunIsolation.requireIsolated(home.resolve("x"), home, temp));

        assertThat(noDefault).isInstanceOf(IOException.class).hasMessageContaining("--out")
                .hasMessageContaining(home.toRealPath().resolve(".git").toString());
        assertThat(refused).isInstanceOf(IOException.class).hasMessageNotContaining("for example");
    }

    @Test
    void dotDotAfterASymbolicLinkIsTheTargetsParentAsTheSystemResolvesIt() throws IOException {
        // other/link -> repo/sub: other/link/../runs is repo/runs, next to repo/CLAUDE.md, though it reads as other/runs.
        Path repo = Files.createDirectories(dir.resolve("repo/sub"));
        Files.writeString(dir.resolve("repo/CLAUDE.md"), "project");
        Path link = Files.createDirectories(dir.resolve("other")).resolve("link");
        try {
            Files.createSymbolicLink(link, repo);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "no symbolic links here");
        }
        Path out = link.resolve("../runs");

        Path resolved = RunIsolation.resolve(out);

        assertThat(resolved).isEqualTo(dir.toRealPath().resolve("repo/runs"));
        assertThat(RunIsolation.leaks(out, dir.resolve("home"))).containsExactly(dir.toRealPath().resolve("repo/CLAUDE.md"));
        assertThat(catchThrowable(() -> RunIsolation.requireIsolated(out, dir.resolve("home"), dir.resolve("tmp"))))
                .isInstanceOf(IOException.class);
        Files.createDirectories(out.resolve("20261113"));
        assertThat(out.resolve("20261113").toRealPath()).isEqualTo(resolved.resolve("20261113"));
    }

    @Test
    void anIsolatedRunFolderComesBackResolvedWithItsMissingTail() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path link = dir.resolve("alias");
        Path out = outside.resolve("a/../b/runs");
        try {
            Files.createSymbolicLink(link, outside);
            out = link.resolve("a/../b/runs");
        } catch (IOException | UnsupportedOperationException e) {
            // no links here: the missing tail is still resolved
        }

        Path resolved = RunIsolation.requireIsolated(out, dir.resolve("home"), dir.resolve("tmp"));

        assertThat(resolved).isEqualTo(outside.toRealPath().resolve("b/runs")).isAbsolute();
        assertThat(resolved.getParent().getParent()).isEqualTo(outside.toRealPath());
    }

    @Test
    void theOldDefaultInsideThisRepositoryIsRefused() {
        // Tests run in msc-bench/, where the old default runs/ put every attempt under the repository's CLAUDE.md.
        assertThat(RunIsolation.leaks(Path.of("runs"), dir))
                .anySatisfy(p -> assertThat(p.getFileName()).hasToString("CLAUDE.md"))
                .anySatisfy(p -> assertThat(p.getFileName()).hasToString(".git"));
    }
}
