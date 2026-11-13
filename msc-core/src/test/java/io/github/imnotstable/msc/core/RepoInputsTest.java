package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A test that reads files outside its module must get them from a repoInputs set (msc.repo-inputs), which is also
 * its task's input; one that walks the repository on its own stays UP-TO-DATE over changes to what it reads, as
 * #129 and #355 did.
 */
class RepoInputsTest {
    /** Ways to reach the repository around the declared sets: the parent of the module folder, or the repo root. */
    private static final Pattern BYPASS = Pattern.compile(
            "(?:Path\\.of|Paths\\.get)\\(\"\\.\\.(?:\"|/)|getProperty\\(\"user\\.dir\"\\)|\"msc\\.repoRoot\"");

    @Test
    void noTestReadsTheRepositoryAroundTheDeclaredSets() throws IOException {
        List<String> sources = RepoFiles.list("testSources");
        assertThat(sources).contains(
                "msc-core/src/test/java/io/github/imnotstable/msc/core/DocumentedCommandsTest.java",
                "msc-bench/src/test/java/io/github/imnotstable/msc/bench/TaskLoaderTest.java",
                "msc-plugin/src/test/java/io/github/imnotstable/msc/plugin/PluginTest.java");
        List<String> found = new ArrayList<>();
        for (String f : sources) {
            if (BYPASS.matcher(Files.readString(RepoFiles.path("testSources", f))).find()) {
                found.add(f);
            }
        }
        assertThat(found).as("read files outside the module through repoInputs.set(...) and RepoFiles").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Path.of(\"..\")", "Paths.get(\"../docs\")", "System.getProperty(\"user.dir\")",
            "Path.of(System.getProperty(\"msc.repoRoot\"))"})
    void recognisesABypass(String code) {
        assertThat(BYPASS.matcher(code).find()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Path.of(\"src/test/resources/x\")", "dir.resolve(\"../second project\")",
            "\"addFiles\":\"../outside\"", "Path.of(\"done.txt\")"})
    void allowsModuleLocalPaths(String code) {
        assertThat(BYPASS.matcher(code).find()).isFalse();
    }

    @Test
    void refusesAFileNoSetDeclares() {
        assertThatThrownBy(() -> RepoFiles.path("documentedCommands", "msc-core/build/tmp/x.md"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not in repoInputs set");
        assertThatThrownBy(() -> RepoFiles.list("undeclared"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("documentedCommands");
    }
}
