package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Builds a plugin project with its Gradle wrapper and finds the jar it produced. */
public final class ProjectBuilder {
    /** @param jar the plugin jar, or null when the build failed or produced none */
    public record BuildResult(boolean ok, String output, Path jar, long millis) {
    }

    private final CoreConfig config;

    public ProjectBuilder(CoreConfig config) {
        this.config = config;
    }

    public BuildResult build(Path projectDir, Duration timeout) throws IOException, InterruptedException {
        if (!Files.isDirectory(projectDir)) {
            throw new ToolException("no project at " + projectDir);
        }
        List<String> command = config.buildCommand();
        if (command == null) {
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            if (!Files.exists(projectDir.resolve(windows ? "gradlew.bat" : "gradlew"))) {
                throw new ToolException("no Gradle wrapper in " + projectDir + ". Create the project with project_scaffold.");
            }
            command = wrapperCommand(projectDir, windows);
        }
        ProcessRunner.Result r = ProcessRunner.run(command, projectDir, timeout);
        String output = r.timedOut() ? r.output() + "\nbuild timed out after " + timeout.toSeconds() + "s" : r.output();
        return new BuildResult(r.ok(), output, r.ok() ? newestJar(projectDir) : null, r.millis());
    }

    /**
     * The wrapper by absolute path: with {@code NoDefaultCurrentDirectoryInExePath} set (Claude Code sets it),
     * cmd does not run a bare {@code gradlew.bat} from the working directory.
     */
    static List<String> wrapperCommand(Path projectDir, boolean windows) {
        Path dir = projectDir.toAbsolutePath();
        return windows
                ? List.of("cmd", "/c", dir.resolve("gradlew.bat").toString(), "build", "--console=plain", "-q")
                : List.of("sh", dir.resolve("gradlew").toString(), "build", "--console=plain", "-q");
    }

    /** Newest jar in build/libs that is not a sources, javadoc or plain jar. */
    static Path newestJar(Path projectDir) throws IOException {
        Path libs = projectDir.resolve("build").resolve("libs");
        if (!Files.isDirectory(libs)) {
            return null;
        }
        try (var files = Files.list(libs)) {
            Optional<Path> jar = files.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar")
                        && !n.endsWith("-plain.jar");
            }).max(Comparator.comparingLong(p -> p.toFile().lastModified()));
            return jar.orElse(null);
        }
    }
}
