package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A test that reads files outside its module must get them from a repoInputs set (msc.repo-inputs), which is also
 * its task's input; one that walks the repository on its own stays UP-TO-DATE over changes to what it reads, as
 * #129 and #355 did. Runs in msc-core's repoChecks task, whose input is every module's test sources (#370).
 */
class RepoInputsTest {
    @Test
    void noTestReadsTheRepositoryAroundTheDeclaredSets() throws IOException {
        List<String> sources = RepoFiles.list("testSources");
        assertThat(sources).contains(
                "msc-core/src/test/java/io/github/imnotstable/msc/core/DocumentedCommandsTest.java",
                "msc-bench/src/test/java/io/github/imnotstable/msc/bench/TaskLoaderTest.java",
                "msc-plugin/src/test/java/io/github/imnotstable/msc/plugin/PluginTest.java");
        List<String> found = new ArrayList<>();
        for (String f : sources) {
            bypasses(Files.readString(RepoFiles.path("testSources", f))).forEach(b -> found.add(f + " " + b));
        }
        assertThat(found).as("test sources that reach repository files around their repoInputs sets").isEmpty();
    }

    static List<String> bypasses(String source) {
        return RepoBypasses.find(source).stream().map(RepoBypasses.Bypass::toString).toList();
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
            "Path tasks = RF.dir(\"s\", \"t\"); read(tasks.resolve(\"x/../../y\"));",
            // A field reaches every method of its class, also ones written above it.
            "class T { static final Path DOCS = RF.dir(\"s\", \"d\"); void m() { read(DOCS.getParent()); } }",
            "class T { void m() { read(DOCS.resolve(\"../x\")); } static final Path DOCS = RF.dir(\"s\", \"d\"); }",
            "record R(int a) { static final Path D = RF.dir(\"s\", \"d\"); Path m() { return D.getParent(); } }",
            // A local reaches the blocks and lambdas nested in its own.
            "void m() { Path p = RF.path(\"s\", \"a\"); if (ok) { read(p.toAbsolutePath().getParent()); } }",
            "void m() { Path p = RF.path(\"s\", \"a\"); list.forEach(x -> read(p.getParent())); }",
            "void m() { java.nio.file.Path p = RF.path(\"s\", \"a\"); return p.getParentFile(); }",
            "void m() { Path p; p = RF.path(\"s\", \"a\"); read(p.getParent()); }",
            "void m() { var p = RF.path(\"s\", \"a\"); read(tmp); read(p.resolveSibling(\"b\")); }"})
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
            "RF.path(\"s\", \"a..b.md\").resolve(\"x..y\")",
            // #381: a variable of the same name in another method, a parameter, a local or a lambda parameter.
            "void m() { Path file = RF.path(SET, \"msc-docs/docs/tools.md\"); read(file); }\n"
                    + "void n(@TempDir Path tmp) { Path file = tmp.resolve(\"reports/a.txt\");\n"
                    + "Files.createDirectories(file.getParent()); }",
            "void m() { Path p = RF.path(SET, \"x.md\"); read(p); } void n(Path p) { Files.createDirectories(p.getParent()); }",
            "class T { static final Path f = RF.dir(\"s\", \"d\"); void n(final Path f) throws IOException { f.getParent(); } }",
            "class T { static final Path f = RF.dir(\"s\", \"d\"); void n() { Path f = tmp; f.getParent(); } }",
            "class T { static final Path f = RF.dir(\"s\", \"d\"); void n() { list.forEach(f -> f.getParent()); } }",
            "class T { static final Path f = RF.dir(\"s\", \"d\"); void n() { all.forEach((a, f) -> { f.getParent(); }); } }",
            "class T { static final Path f = RF.dir(\"s\", \"d\"); void n() { for (Path f : temps) { f.getParent(); } } }",
            "class T { static final Path f = RF.dir(\"s\", \"d\"); void n() { for (Path f : temps) f.getParent(); } }",
            "class T { static final Path f = RF.dir(\"s\", \"d\"); void n() { if (o instanceof Path f) { f.getParent(); } } }",
            "void m() { for (String n : names) { Path f = RF.path(\"s\", n); read(f); } Path f = tmp; f.getParent(); }",
            "void m() { Path p = RF.path(\"s\", \"a\"); read(p); p = tmp.resolve(\"x\"); p.getParent(); }",
            "// Path.of(\"..\"), user.dir: q = RF.path(\"s\", \"a\"); q.getParent()\nq.getParent();",
            "/* p = RF.path(\"s\", \"a\"); */ p.getParent();",
            "String s = \"p = RF.path(a)\"; p.getParent();",
            "Path f = RF.path(\"s\", \"a\"); f2.getParent(); g.f.getParent(); f.getFileName();"})
    void allowsModuleLocalPaths(String code) {
        assertThat(bypasses(code.replace("RF.", "RepoFiles."))).isEmpty();
    }

    @Test
    void saysWhereAndWhatToChange() {
        assertThat(bypasses("class T {\n  void m() {\n    Path doc = RepoFiles" + ".path(\"s\", \"a.md\");\n"
                + "    read(doc.getParent());\n  }\n}")).singleElement().asString()
                .startsWith("line 4: doc.getParent( -- ").contains("RepoFiles.dir(set, folder)")
                .contains("'doc' holds the RepoFiles path from line 3").contains("rename one of them");
        assertThat(bypasses("x = Path.of(\"..\");")).singleElement().asString()
                .startsWith("line 1: Path.of(\"..\" -- ").contains("repoInputs.set(").contains("RepoFiles.path(");
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
