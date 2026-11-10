package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scaffolds the Paper plugin template and runs its own {@code gradlew build}, which compiles it
 * against Paper and runs its MockBukkit tests. Only runs via {@code ./gradlew :msc-templates:verifyTemplates}.
 */
@Tag("template-build")
class TemplateBuildTest {
    @Test
    void paperPluginBuildsAndPassesItsTests() throws IOException, InterruptedException {
        Path dir = Path.of(System.getProperty("msc.templates.verifyDir")).resolve("paper-plugin");
        deleteRecursively(dir);
        ScaffoldResult r = Templates.scaffold(Templates.PAPER_PLUGIN, dir, TemplateVariables.of("Verify Me"));

        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> command = windows
                ? List.of("cmd", "/c", "gradlew.bat", "build", "--no-daemon", "-q")
                : List.of("./gradlew", "build", "--no-daemon", "-q");
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(dir.resolve("build-output.txt").toFile()).start();
        boolean finished = p.waitFor(15, TimeUnit.MINUTES);
        String output = Files.readString(dir.resolve("build-output.txt"));

        assertThat(finished).as("build finished in time").isTrue();
        assertThat(p.exitValue()).as(output).isZero();
        assertThat(r.root().resolve("build/libs/verify-me-0.1.0-SNAPSHOT.jar")).exists();
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
