package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scaffolds each project template and runs its own {@code gradlew build}, which compiles it
 * against Paper, Fabric, NeoForge, Forge or Minestom and runs its tests. Only runs via
 * {@code ./gradlew :msc-templates:verifyTemplates}. The same run lists the project's tasks, and every
 * {@code ./gradlew <task>} its CLAUDE.md and README.md name must be among them.
 */
@Tag("template-build")
class TemplateBuildTest {
    @Test
    void paperPluginBuildsAndPassesItsTests() throws IOException, InterruptedException {
        buildsAndPassesItsTests(Templates.PAPER_PLUGIN);
    }

    @Test
    void paperGameBuildsAndPassesItsTests() throws IOException, InterruptedException {
        buildsAndPassesItsTests(Templates.PAPER_GAME);
    }

    @Test
    void fabricModBuildsAndPassesItsTests() throws IOException, InterruptedException {
        buildsAndPassesItsTests(Templates.FABRIC_MOD);
    }

    @Test
    void neoforgeModBuildsAndPassesItsTests() throws IOException, InterruptedException {
        buildsAndPassesItsTests(Templates.NEOFORGE_MOD);
    }

    @Test
    void forgeModBuildsAndPassesItsTests() throws IOException, InterruptedException {
        buildsAndPassesItsTests(Templates.FORGE_MOD);
    }

    @Test
    void minestomServerBuildsAndPassesItsTests() throws IOException, InterruptedException {
        buildsAndPassesItsTests(Templates.MINESTOM_SERVER);
    }

    @Test
    void minestomGameBuildsAndPassesItsTests() throws IOException, InterruptedException {
        buildsAndPassesItsTests(Templates.MINESTOM_GAME);
    }

    @Test
    void fabricGameBuildsAndPassesItsTests() throws IOException, InterruptedException {
        buildsAndPassesItsTests(Templates.FABRIC_GAME);
    }

    private static void buildsAndPassesItsTests(String id) throws IOException, InterruptedException {
        ScaffoldResult r = scaffoldFresh(id, id);
        String output = gradle(r.root(), "build", "tasks", "--all");

        assertThat(r.root().resolve("build/libs/verify-me-0.1.0-SNAPSHOT.jar")).exists();
        // Every task the docs say to run is one this build really has (#371); TemplateDocsTest reads the build file.
        List<String> tasks = output.lines().map(l -> l.split(" ", 2)[0]).toList();
        for (TemplateDocs.Command c : TemplateDocs.commands(r.root())) {
            assertThat(tasks).as(c.doc() + ": " + c.line()).contains(c.task());
        }
    }

    /** Scaffolds template {@code id} for "Verify Me" into a fresh {@code <verifyDir>/<dirName>}. */
    static ScaffoldResult scaffoldFresh(String id, String dirName) throws IOException {
        Path dir = Path.of(System.getProperty("msc.templates.verifyDir")).resolve(dirName);
        deleteRecursively(dir);
        return Templates.scaffold(id, dir, TemplateVariables.of("Verify Me"));
    }

    /** Runs the project's own wrapper with these tasks, asserts it passed and returns its output. */
    static String gradle(Path dir, String... tasks) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> command = new ArrayList<>(windows
                // Explicit .\\ path: cmd may not search the current directory (NoDefaultCurrentDirectoryInExePath).
                ? List.of("cmd", "/c", ".\\gradlew.bat")
                : List.of("./gradlew"));
        command.addAll(List.of(tasks));
        command.addAll(List.of("--no-daemon", "-q"));
        Path log = dir.resolve("build-output.txt");
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        boolean finished = p.waitFor(15, TimeUnit.MINUTES);
        String output = Files.readString(log);

        assertThat(finished).as("build finished in time").isTrue();
        assertThat(p.exitValue()).as(output).isZero();
        return output;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
