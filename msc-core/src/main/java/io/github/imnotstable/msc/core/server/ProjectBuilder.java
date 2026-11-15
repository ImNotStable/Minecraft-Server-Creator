package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a project with its Gradle wrapper and finds the jar it produced. {@link #devJar} builds only what the dev
 * server loads ({@code mscJar}, from an init script the core writes), {@link #check} runs the tests.
 */
public final class ProjectBuilder {
    /**
     * @param jar  the jar to deploy, or null when the build failed or produced none
     * @param libs for a thin server jar, the folder of dependency jars its manifest names (deployed beside it as
     *             {@code libs/}); otherwise null
     */
    public record BuildResult(boolean ok, String output, Path jar, long millis, Path libs) {
    }

    /** The task the init script adds: the deployable jar and nothing else that {@code assemble} would build. */
    static final String DEV_JAR_TASK = "mscJar";
    private static final String INIT_SCRIPT = "msc-init.gradle";

    private final CoreConfig config;

    public ProjectBuilder(CoreConfig config) {
        this.config = config;
    }

    /**
     * Only the jar the dev server loads: no tests, no distribution archives. {@code thin} (a server that is its own
     * project, as on Minestom) builds a small {@code build/msc/server.jar} whose manifest points at
     * {@code libs/}, instead of rewriting a fat jar of every dependency on each edit.
     */
    public BuildResult devJar(Path projectDir, Duration timeout, boolean thin) throws IOException, InterruptedException {
        List<String> extra = new ArrayList<>(List.of("--init-script", initScript().toString()));
        if (thin) {
            extra.add("-Pmsc.thin=true");
        }
        BuildResult r = run(projectDir, timeout, List.of(DEV_JAR_TASK), extra, false);
        Path out = projectDir.resolve("build").resolve("msc");
        if (r.ok() && thin && Files.isRegularFile(out.resolve("server.jar")) && Files.isDirectory(out.resolve("libs"))) {
            return new BuildResult(true, r.output(), out.resolve("server.jar"), r.millis(), out.resolve("libs"));
        }
        return r;
    }

    /**
     * Everything a first {@code dev_cycle} runs, the dev jar and {@code check}, to fill Gradle's and the loader's
     * caches ahead of it. Failures are not reported: the next real build shows them.
     */
    public BuildResult warm(Path projectDir, Duration timeout) throws IOException, InterruptedException {
        return run(projectDir, timeout, List.of(DEV_JAR_TASK, "check"),
                List.of("--init-script", initScript().toString(), "--continue"), true);
    }

    /** The tests and other checks; the jar is not looked for. */
    public BuildResult check(Path projectDir, Duration timeout) throws IOException, InterruptedException {
        return run(projectDir, timeout, List.of("check"), List.of(), true);
    }

    /**
     * Run Gradle tasks. A configured build command gets the task names appended, as the wrapper does, but not the
     * wrapper's extra arguments.
     */
    private BuildResult run(Path projectDir, Duration timeout, List<String> tasks, List<String> extra, boolean noJar)
            throws IOException, InterruptedException {
        if (!Files.isDirectory(projectDir)) {
            throw new ToolException("no project at " + projectDir);
        }
        List<String> command;
        if (config.buildCommand() != null) {
            command = new ArrayList<>(config.buildCommand());
            command.addAll(tasks);
        } else {
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            if (!Files.exists(projectDir.resolve(windows ? "gradlew.bat" : "gradlew"))) {
                throw new ToolException("no Gradle wrapper in " + projectDir + ". " + noWrapperStep(projectDir));
            }
            List<String> args = new ArrayList<>(tasks);
            args.addAll(extra);
            command = wrapperCommand(projectDir, windows, args.toArray(String[]::new));
        }
        // The Gradle user home the core reads the caches from (api_search), so the build fills those (#666).
        ProcessRunner.Result r = ProcessRunner.run(command, projectDir, timeout,
                Map.of(GradleUserHome.ENV, config.gradleUserHome().toString()));
        String output = r.timedOut() ? r.output() + "\nbuild timed out after " + timeout.toSeconds() + "s" : r.output();
        return new BuildResult(r.ok(), output, r.ok() && !noJar ? newestJar(projectDir) : null, r.millis(), null);
    }

    /**
     * What works instead of a folder with no wrapper: a project the workspace's server runs in a subfolder of it, at
     * any depth (#665, #679), else the workspace's next step (#680).
     */
    private String noWrapperStep(Path projectDir) {
        List<Path> found = WorkspaceProjects.find(config, projectDir).stream()
                .filter(p -> WorkspaceProjects.served(config, p)).toList();
        if (!found.isEmpty()) {
            return "A subfolder holds one: " + WorkspaceProjects.devCycles(config, found) + ".";
        }
        return "Run " + WorkspaceProjects.nextDevCycle(config) + ".";
    }

    /** {@code .msc/msc-init.gradle}, rewritten when this core ships a different one. */
    private Path initScript() throws IOException {
        byte[] script;
        try (InputStream in = ProjectBuilder.class.getResourceAsStream(INIT_SCRIPT)) {
            if (in == null) {
                throw new IOException(INIT_SCRIPT + " missing from msc-core");
            }
            script = in.readAllBytes();
        }
        Path file = config.dataDir().resolve(INIT_SCRIPT);
        if (!Files.exists(file) || !Arrays.equals(Files.readAllBytes(file), script)) {
            Files.createDirectories(file.getParent());
            Files.write(file, script);
        }
        return file.toAbsolutePath();
    }

    /**
     * Not {@code -q}: quiet mode hides which tests failed and why (test events log at lifecycle level).
     * {@link LogCondenser#condenseBuild} drops Gradle's own chatter instead.
     */
    private static final List<String> FLAGS = List.of("--console=plain", "-Dorg.gradle.welcome=never");

    /**
     * The wrapper by path, run from the project folder. On Windows {@code .\gradlew.bat}: with
     * {@code NoDefaultCurrentDirectoryInExePath} set (Claude Code sets it) cmd does not run a bare {@code gradlew.bat},
     * and a quoted first word (an absolute path with spaces) makes {@code cmd /c} strip the quotes as soon as another
     * argument, such as the init script, is quoted too.
     */
    static List<String> wrapperCommand(Path projectDir, boolean windows, String... tasks) {
        List<String> command = new ArrayList<>(windows
                ? List.of("cmd", "/c", ".\\gradlew.bat")
                : List.of("sh", projectDir.toAbsolutePath().resolve("gradlew").toString()));
        command.addAll(List.of(tasks));
        command.addAll(FLAGS);
        return List.copyOf(command);
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
