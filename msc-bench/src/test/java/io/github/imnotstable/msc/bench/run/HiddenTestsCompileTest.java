package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.check.Check;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #610: the files a check adds to a scaffolded project (such as Sky Sprint's hidden {@code BenchSprintTest}) call the
 * template's API, which the templates piece may change. Every bundled task that scaffolds a template and has a check
 * with {@code addFiles} is set up as a run sets it up (the scaffold, the fixtures, then the added files) and the
 * project's main and test sources are compiled, so a template change that breaks those files fails the build instead
 * of every attempt of a real run.
 *
 * <p>The compile is javac in this JVM against the libraries the template's own {@code gradle/libs.versions.toml}
 * lists, which the build resolves from the repository's catalog and passes as {@code msc.bench.templateClasspath.<id>},
 * plus the jars the scaffold puts in {@code libs/}: no Gradle build of the scaffolded project, no network, a few
 * seconds.
 */
class HiddenTestsCompileTest {

    /** The bundled tasks: the build's bundledTasks set, so editing a task reruns these tests. */
    private static final Path BUNDLED = RepoFiles.dir("bundledTasks", "msc-bench/tasks");

    @TempDir
    Path dir;

    /** Each scaffolded task's checks that add files, named by task and check. */
    static Stream<Arguments> addedFiles() throws IOException {
        List<Arguments> rows = new ArrayList<>();
        for (TaskDef task : TaskLoader.loadAll(BUNDLED)) {
            if (task.scaffold() == null) {
                continue;
            }
            for (Check check : task.checks()) {
                if (check.addFiles() != null) {
                    rows.add(Arguments.argumentSet(task.id() + ": " + check.name(), task, check.addFiles()));
                }
            }
        }
        return rows.stream();
    }

    @Test
    void someTaskAddsFilesToAScaffoldedProject() throws IOException {
        // Without one the test below would pass by running nothing.
        assertThat(addedFiles()).isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("addedFiles")
    void theAddedFilesCompileAgainstTheScaffoldedTemplate(TaskDef task, Path added) throws IOException {
        String template = task.scaffold().template();
        String libraries = System.getProperty("msc.bench.templateClasspath." + template);
        assertThat(libraries).as("%s scaffolds %s, which has no compile classpath: add it to templateClasspaths in "
                + "msc-bench/build.gradle.kts", task.id(), template).isNotNull();
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Workspaces.scaffold(task.scaffold(), workspace, BenchConfig.defaults().server().serverPort());
        for (Path fixture : task.fixtures()) {
            Workspaces.copyTree(fixture, workspace);
        }
        Workspaces.copyTree(added, workspace);

        List<String> errors = compile(workspace, libraries);

        assertThat(errors).as("%s: %s from %s no longer compile against the %s template; change the added files "
                + "(and the API the prompt lists) or the template", task.id(), added.getFileName(),
                BUNDLED.relativize(added), template).isEmpty();
    }

    /** Compiles the project's main and test sources together; returns each error as {@code file:line: message}. */
    private List<String> compile(Path workspace, String libraries) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/test/java")) {
            Path folder = workspace.resolve(root);
            if (Files.isDirectory(folder)) {
                try (Stream<Path> files = Files.walk(folder)) {
                    files.filter(f -> f.toString().endsWith(".java")).sorted().forEach(sources::add);
                }
            }
        }
        assertThat(sources).as("no Java sources in the scaffolded project").isNotEmpty();
        List<String> classpath = new ArrayList<>(List.of(libraries.split(File.pathSeparator)));
        Path libs = workspace.resolve("libs");
        if (Files.isDirectory(libs)) {
            try (Stream<Path> jars = Files.list(libs)) {
                jars.filter(j -> j.toString().endsWith(".jar")).sorted().map(Path::toString).forEach(classpath::add);
            }
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            List<String> options = List.of("-proc:none", "-nowarn", "-encoding", "UTF-8",
                    "-d", Files.createDirectories(dir.resolve("classes")).toString(),
                    "-cp", String.join(File.pathSeparator, classpath));
            javac.getTask(new StringWriter(), files, diagnostics, options, null,
                    files.getJavaFileObjectsFromPaths(sources)).call();
        }
        return diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> (d.getSource() == null ? "" : workspace.relativize(Path.of(d.getSource().toUri()))
                        + ":" + d.getLineNumber() + ": ") + d.getMessage(Locale.ROOT))
                .toList();
    }
}
