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
 * #129 and #355 did. Runs in msc-core's repoChecks task, whose input is every module's test sources (#370).
 */
class RepoInputsTest {
    private static final String ARGS = "(?:[^()]|\\([^()]*\\))*";
    /** One step up from a path: to its parent or a sibling, or through a {@code ..} segment. */
    private static final String WALK = "(?:\\s*\\.\\w+\\(" + ARGS + "\\))*?\\s*\\.(?:getParent\\(|getParentFile\\("
            + "|resolveSibling\\(|resolve\\(\"(?:[^\"]*/)?\\.\\.(?:/|\"))";
    /**
     * Ways to reach the repository around the declared sets: the parent of the module folder (as a path, a file or
     * up from the working folder), the repo root, or up from a declared file or folder (#370).
     */
    private static final Pattern BYPASS = Pattern.compile(
            "(?:Path\\.of|Paths\\.get|new File)\\(\"\\.\\.(?:\"|/)"
            + "|(?:Path\\.of|Paths\\.get|new File)\\(\"\\.?\"\\)" + WALK
            + "|getProperty\\(\"user\\.dir\"\\)|\"msc\\.repoRoot\""
            + "|RepoFiles\\.(?:path|dir)\\(" + ARGS + "\\)" + WALK);
    /** A variable holding a declared file or folder, to find walks up from it later in the source. */
    private static final Pattern DECLARED = Pattern.compile("(\\w+)\\s*=\\s*RepoFiles\\.(?:path|dir)\\(");

    @Test
    void noTestReadsTheRepositoryAroundTheDeclaredSets() throws IOException {
        List<String> sources = RepoFiles.list("testSources");
        assertThat(sources).contains(
                "msc-core/src/test/java/io/github/imnotstable/msc/core/DocumentedCommandsTest.java",
                "msc-bench/src/test/java/io/github/imnotstable/msc/bench/TaskLoaderTest.java",
                "msc-plugin/src/test/java/io/github/imnotstable/msc/plugin/PluginTest.java");
        List<String> found = new ArrayList<>();
        for (String f : sources) {
            bypasses(Files.readString(RepoFiles.path("testSources", f))).forEach(b -> found.add(f + ": " + b));
        }
        assertThat(found).as("read files outside the module through repoInputs.set(...) and RepoFiles, and name "
                + "the folder you need with RepoFiles.dir(set, folder) instead of walking up").isEmpty();
    }

    /** The bypasses in {@code source}: the text of each, from a direct match or a walk up from a declared path. */
    static List<String> bypasses(String source) {
        List<String> found = new ArrayList<>();
        BYPASS.matcher(source).results().forEach(m -> found.add(m.group()));
        for (String var : DECLARED.matcher(source).results().map(m -> m.group(1)).distinct().toList()) {
            Pattern.compile("(?<![\\w.])" + var + WALK).matcher(source).results().forEach(m -> found.add(m.group()));
        }
        return found;
    }

    /** RF. stands for RepoFiles., so these lines do not match in this file's own source. */
    @ParameterizedTest
    @ValueSource(strings = {"Path.of(\"..\")", "Paths.get(\"../docs\")", "System.getProperty(\"user.dir\")",
            "Path.of(System.getProperty(\"msc.repoRoot\"))", "new File(\"..\")", "new File(\"../docs/x.md\")",
            "Path.of(\"\").toAbsolutePath().getParent()", "new File(\".\").getAbsoluteFile().getParentFile()",
            "Paths.get(\"\").toAbsolutePath().normalize().resolve(\"../x\")",
            "RF.path(\"s\", \"CLAUDE.md\").getParent()",
            "RF.path(SET, name(\"x\")).toAbsolutePath()\n        .getParent().resolve(\"docs\")",
            "RF.path(\"s\", \"a/b.md\").resolveSibling(\"c.md\")",
            "RF.dir(\"s\", \"msc-bench/tasks\").resolve(\"../README.md\")",
            "RF.path(\"s\", \"a/b.md\").toFile().getParentFile()",
            "Path doc = RF.path(\"s\", \"a/b.md\");\n Files.readString(doc.getParent().resolve(\"c.md\"));",
            "Path tasks = RF.dir(\"s\", \"t\"); read(tasks.resolve(\"x/../../y\"));"})
    void recognisesABypass(String code) {
        assertThat(bypasses(code.replace("RF.", "RepoFiles."))).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Path.of(\"src/test/resources/x\")", "dir.resolve(\"../second project\")",
            "\"addFiles\":\"../outside\"", "Path.of(\"done.txt\")", "Files.createDirectories(file.getParent())",
            "Path.of(\"\").toAbsolutePath().toRealPath()", "new File(\"x..y\")",
            "RF.dir(\"s\", \"\").resolve(\"msc-bench/runs\")",
            "RF.dir(\"s\", \"msc-bench/tasks\").resolve(\"a/b.json\")",
            "json(RF.path(F, \"x.json\")).getAsJsonObject(\"a\").getParent()",
            "Path f = RF.path(\"s\", n); Files.createDirectories(tmpf.getParent()); g.f.getParent();",
            "RF.path(\"s\", \"a..b.md\").resolve(\"x..y\")"})
    void allowsModuleLocalPaths(String code) {
        assertThat(bypasses(code.replace("RF.", "RepoFiles."))).isEmpty();
    }

    @Test
    void refusesAFileNoSetDeclares() {
        assertThatThrownBy(() -> RepoFiles.path("documentedCommands", "msc-core/build/tmp/x.md"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not in repoInputs set");
        assertThatThrownBy(() -> RepoFiles.list("undeclared"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(":msc-core:repoChecks").hasMessageContaining("documentedCommands");
    }
}
