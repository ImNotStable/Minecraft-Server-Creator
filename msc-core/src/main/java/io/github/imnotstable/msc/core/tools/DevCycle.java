package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.server.LogCondenser;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ProjectBuilder;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * {@code dev_cycle}: build, deploy, restart, report. The jar is built first ({@code mscJar}: only the jar, and on
 * Minestom a thin one with its dependencies in {@code libs/}); the tests
 * ({@code check}) then run in a second Gradle call while the server restarts, so their time is hidden behind the
 * start. A jar identical to the one the running server loaded keeps the server running. Each phase is timed.
 */
final class DevCycle {
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);

    private final CoreConfig config;
    private final ServerManager server;
    private final ProjectBuilder builder;
    private final ServerRun run;
    private final BuildWarmup warmup;
    private final BackgroundWork background;

    DevCycle(CoreConfig config, ServerManager server, ProjectBuilder builder, ServerRun run, BuildWarmup warmup,
             BackgroundWork background) {
        this.config = config;
        this.server = server;
        this.builder = builder;
        this.run = run;
        this.warmup = warmup;
        this.background = background;
    }

    /** Phase timings, shown on one line at the end so a slow phase is easy to spot. */
    static final class Timings {
        private final long begin = System.nanoTime();
        private final List<String> parts = new ArrayList<>();

        void add(String phase, long millis) {
            parts.add(phase + " " + LogText.seconds(millis));
        }

        String line() {
            return "time: " + String.join(", ", parts) + ", total "
                    + LogText.seconds((System.nanoTime() - begin) / 1_000_000);
        }
    }

    String run(Args args) throws IOException, InterruptedException {
        int maxChars = Budget.of(args);
        Path project = config.resolve(args.str("project", "."));
        boolean restart = args.bool("restart", true);
        Timings time = new Timings();
        long warmed = warmup.await(project);
        if (warmed >= 0) {
            time.add("waited for first build", warmed);
        }
        Platform platform = platform(project);
        ProjectBuilder.BuildResult jar = builder.devJar(project, BUILD_TIMEOUT, platform.addonDir() == null);
        time.add("jar", jar.millis());
        if (!jar.ok()) {
            return "BUILD FAILED in " + LogText.seconds(jar.millis()) + "\n"
                    + LogCondenser.condenseBuild(jar.output(), maxChars);
        }
        Check tests = check(project);
        StringBuilder out = new StringBuilder();
        String rest;
        ProjectBuilder.BuildResult checked;
        try {
            rest = deployAndRestart(project, platform, jar, restart, maxChars, time);
            checked = await(tests.result());
        } finally {
            // On any early end (a failed deploy, an interrupt while waiting for the tests) stop the tests for real:
            // the next dev_cycle would otherwise start a second Gradle build beside them.
            if (!tests.result().isDone()) {
                tests.thread().interrupt();
            }
        }
        time.add("tests", checked.millis());
        if (!checked.ok()) {
            out.append("TESTS FAILED in ").append(LogText.seconds(checked.millis()))
                    .append(jar.jar() == null ? "" : " (the new jar is deployed anyway)").append('\n')
                    .append(LogCondenser.condenseBuild(checked.output(), maxChars / 2)).append('\n');
        }
        return out.append(rest).append('\n').append(time.line()).toString();
    }

    /** The tests running beside the deploy, and the thread to interrupt to stop them. */
    private record Check(Thread thread, CompletableFuture<ProjectBuilder.BuildResult> result) {
    }

    /** Run the tests on another thread: the jar is already built, so they only race the server start. */
    private Check check(Path project) {
        CompletableFuture<ProjectBuilder.BuildResult> f = new CompletableFuture<>();
        Thread thread = background.start("msc-dev-cycle-check", () -> {
            try {
                f.complete(builder.check(project, BUILD_TIMEOUT));
            } catch (IOException | RuntimeException e) {
                f.completeExceptionally(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                f.completeExceptionally(e);
            }
        });
        return new Check(thread, f);
    }

    private static ProjectBuilder.BuildResult await(CompletableFuture<ProjectBuilder.BuildResult> f)
            throws IOException, InterruptedException {
        try {
            return f.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /**
     * The project's platform when the workspace has none yet ({@link #rememberPlatform} keeps it once deployed); else
     * the workspace's. A workspace set up as a platform that runs the same projects (Quilt for a Fabric mod, Purpur
     * for a Paper plugin) keeps its own.
     */
    private Platform platform(Path project) {
        Platform platform = Platform.detect(project);
        if (platform == null) {
            return server.platform();
        }
        if (!Files.exists(Platform.file(config))) {
            return platform;
        }
        Platform workspace = server.platform();
        return workspace.agent().equals(platform.agent()) ? workspace : platform;
    }

    private String deployAndRestart(Path project, Platform platform, ProjectBuilder.BuildResult build, boolean restart,
                                    int maxChars, Timings time) throws IOException, InterruptedException {
        if (build.jar() == null) {
            return "build ok in " + LogText.seconds(build.millis()) + " but no jar in " + project.resolve("build/libs")
                    + "; nothing deployed";
        }
        StringBuilder out = new StringBuilder();
        // Before deploying: a server an earlier core left running holds the old jar open.
        long t = System.nanoTime();
        String leftover = server.stopLeftover();
        if (leftover != null) {
            out.append(leftover).append('\n');
            time.add("leftover stop", millisSince(t));
        }
        out.append("build ok in ").append(LogText.seconds(build.millis()));
        Path target = target(platform, build.jar(), project);
        String shown = platform.addonDir() == null ? platform.serverJar() : platform.addonDir() + "/" + target.getFileName();
        boolean wasRunning = server.running();
        Path libs = config.serverDir().resolve("libs");
        if (restart && wasRunning && loaded(build.jar(), target)
                && (build.libs() == null || mirror(build.libs(), libs, true) == 0)) {
            return out.append(", jar unchanged: ").append(shown).append(" kept, server not restarted (server_stop then "
                    + "server_start restarts it)").append(run.resendPack()).toString();
        }
        if (wasRunning && restart) {
            t = System.nanoTime();
            server.stop(Duration.ofSeconds(30));
            time.add("stop", millisSince(t));
        }
        if (platform.addonDir() != null) {
            removeEarlierDeploy(platform, project, target.getParent(), target);
        }
        copy(build.jar(), target);
        if (build.libs() != null) {
            mirror(build.libs(), libs, false);
        }
        rememberDeploy(project, target);
        rememberPlatform(project, platform);
        out.append(", deployed ").append(build.jar().getFileName()).append(" as ").append(shown);
        if (!restart) {
            return out.append(wasRunning ? "; server still running the old jar until restarted" : "").toString();
        }
        if (server.serverJar() == null && config.serverCommand() == null) {
            return out.append("\nserver not set up, so nothing was started. Run server_setup, then dev_cycle.").toString();
        }
        ServerRun.Report report = run.startAndReport(180, maxChars);
        time.add("start", report.result().millis());
        if (report.result().agentMillis() >= 0) {
            time.add("agent", report.result().agentMillis());
        }
        return out.append('\n').append(report.text()).toString();
    }

    /**
     * A workspace with no platform yet takes the deployed project's, but only once the jar is deployed: a failed build
     * or a refused deploy leaves the workspace as it was.
     */
    private void rememberPlatform(Path project, Platform platform) throws IOException {
        if (Platform.detect(project) != null && !Files.exists(Platform.file(config))) {
            Platform.remember(config, platform);
        }
    }

    private static long millisSince(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000;
    }

    /** Where the jar goes: the addon folder, or for Minestom the server jar itself (the project is the server). */
    private Path target(Platform platform, Path jar, Path project) throws IOException {
        if (platform.addonDir() == null) {
            Files.createDirectories(config.serverDir());
            return config.serverDir().resolve(platform.serverJar());
        }
        String name = deployName(jar, project) + ".jar";
        if (isAgentJar(platform, name)) {
            throw new ToolException("the project's jar deploys as " + platform.addonDir() + "/" + name
                    + ", which is MSC's agent jar on " + platform.displayName() + ": deploying it would replace the agent."
                    + " Rename the project (rootProject.name in settings.gradle.kts), then dev_cycle.");
        }
        Path addons = Files.createDirectories(config.serverDir().resolve(platform.addonDir()));
        return addons.resolve(name);
    }

    /** Ignoring case, as Windows and macOS file names do: {@code MSC-Agent-Paper.jar} is the agent jar there too. */
    static boolean isAgentJar(Platform platform, String fileName) {
        return fileName.equalsIgnoreCase(platform.agentJar());
    }

    /**
     * True when the running server already loaded this exact jar: same bytes at the target, written before the
     * server started. Gradle 9 jars are reproducible, so an unchanged project gives an identical jar.
     */
    private boolean loaded(Path jar, Path target) throws IOException {
        Instant started = server.startedAt();
        return started != null && Files.isRegularFile(target)
                && !Files.getLastModifiedTime(target).toInstant().isAfter(started)
                && Files.mismatch(jar, target) == -1;
    }

    /**
     * Make {@code dest} hold exactly the jars in {@code src}, copying only the ones that differ. Returns how many
     * files were (or with {@code dryRun}, would be) written or deleted.
     */
    static int mirror(Path src, Path dest, boolean dryRun) throws IOException {
        int changed = 0;
        java.util.Set<String> keep = new java.util.HashSet<>();
        try (var files = Files.list(src)) {
            for (Path from : files.toList()) {
                String name = from.getFileName().toString();
                keep.add(name);
                Path to = dest.resolve(name);
                if (Files.isRegularFile(to) && Files.size(to) == Files.size(from) && Files.mismatch(from, to) == -1) {
                    continue;
                }
                changed++;
                if (!dryRun) {
                    Files.createDirectories(dest);
                    copy(from, to);
                }
            }
        }
        if (Files.isDirectory(dest)) {
            try (var files = Files.list(dest)) {
                for (Path old : files.toList()) {
                    if (!keep.contains(old.getFileName().toString())) {
                        changed++;
                        if (!dryRun) {
                            Files.delete(old);
                        }
                    }
                }
            }
        }
        return changed;
    }

    private static void copy(Path from, Path to) {
        try {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ToolException("cannot replace " + to + " (" + e.getMessage()
                    + "). A running server holds the jar open: call server_stop, then dev_cycle.");
        }
    }

    /** {@code .msc/deployed.properties}: project folder to the jar dev_cycle last deployed for it. */
    private Path deployedFile() {
        return config.dataDir().resolve("deployed.properties");
    }

    private Properties deployed() throws IOException {
        Properties p = new Properties();
        if (Files.exists(deployedFile())) {
            try (InputStream in = Files.newInputStream(deployedFile())) {
                p.load(in);
            }
        }
        return p;
    }

    /**
     * Delete the jar an earlier dev_cycle deployed for this project under another name, so the server never loads
     * two copies (Paper: ambiguous plugin name; Fabric: duplicate mod id). Without a record, the folder-named jar
     * that earlier cores deployed is the earlier one.
     */
    private void removeEarlierDeploy(Platform platform, Path project, Path addons, Path target) throws IOException {
        String recorded = deployed().getProperty(project.toAbsolutePath().normalize().toString());
        Path earlier;
        if (recorded != null) {
            earlier = config.serverDir().resolve(recorded).normalize();
        } else {
            Path folder = project.toAbsolutePath().normalize().getFileName();
            earlier = folder == null ? null : addons.resolve(folder + ".jar");
        }
        // Never the agent's jar: a project folder named msc-agent-paper, or a record from before #311, names it.
        if (earlier != null && !earlier.equals(target) && earlier.startsWith(addons) && Files.isRegularFile(earlier)
                && !isAgentJar(platform, earlier.getFileName().toString())) {
            try {
                Files.delete(earlier);
            } catch (IOException e) {
                throw new ToolException("cannot remove the earlier deploy " + earlier + " (" + e.getMessage()
                        + "). A running server holds the jar open: call server_stop, then dev_cycle.");
            }
        }
    }

    private void rememberDeploy(Path project, Path target) throws IOException {
        Properties p = deployed();
        p.setProperty(project.toAbsolutePath().normalize().toString(),
                config.serverDir().relativize(target).toString().replace('\\', '/'));
        Files.createDirectories(config.dataDir());
        try (OutputStream out = Files.newOutputStream(deployedFile())) {
            p.store(out, "jar dev_cycle last deployed per project");
        }
    }

    /**
     * A trailing version: digits and dots, then qualifiers that start with a letter ({@code -SNAPSHOT}, {@code -rc1})
     * and build metadata ({@code +26.3}). So name parts like {@code parkour-2} or {@code bedwars-4v4} survive.
     */
    private static final String VERSION_SUFFIX = "-\\d+(\\.\\d+)*(-[A-Za-z][\\w.]*)*(\\+[\\w.+-]*)?$";

    /**
     * The deployed jar's name: the built jar's name without its version ({@code lobby-0.1.0-SNAPSHOT.jar} becomes
     * {@code lobby}), so it stays the same across versions and does not depend on the folder name.
     */
    static String deployName(Path jar, Path project) {
        String base = jar.getFileName().toString().replaceFirst("\\.jar$", "").replaceFirst(VERSION_SUFFIX, "");
        if (!base.isEmpty()) {
            return base;
        }
        return project.getFileName() == null ? "project" : project.getFileName().toString();
    }
}
