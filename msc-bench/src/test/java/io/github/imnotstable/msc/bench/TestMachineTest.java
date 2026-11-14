package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The stand-in machine msc-bench's build.gradle.kts gives every test (#335): the tests pass on it, so their results
 * cannot depend on the real home, the real temporary folder, what lies above it, the working folder or the
 * variables the fakes read. If this fails, the other tests are running on the real machine again.
 */
class TestMachineTest {

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void theHomeIsAFakeThatIsAProjectInEveryWayClaudeCodeKnows() throws IOException {
        Path repo = RepoFiles.dir("repoClaudeMd", "").toRealPath();

        assertThat(HOME.toRealPath()).startsWith(repo.resolve("msc-bench/build"));
        assertThat(HOME.resolve(".claude/CLAUDE.md")).isRegularFile();
        assertThat(HOME.resolve("CLAUDE.md")).isRegularFile();
        assertThat(HOME.resolve(".git")).isDirectory();
        assertThat(HOME.resolve(".msc")).as("no machine-wide EULA acceptance or runs").doesNotExist();
    }

    @Test
    void theTemporaryAndWorkingFoldersLieInsideIt() throws IOException {
        Path home = HOME.toRealPath();

        assertThat(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()).startsWith(home);
        assertThat(Path.of("").toAbsolutePath().toRealPath()).startsWith(home);
    }

    @Test
    void theVariablesTheFakesReadAreCleared() {
        assertThat(System.getenv().keySet()).noneMatch(k -> k.startsWith("FAKE_") || k.startsWith("MSC_"));
    }
}
