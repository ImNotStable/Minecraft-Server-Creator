package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.files.AtomicFile;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiPredicate;

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
     * The project's platform when the workspace has none yet (the deploy remembers it); else
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

    static final String STOP_LEFTOVER = "stop a server an earlier session left holding the jar";
    static final String STOP = "stop the server";
    static final String DEPLOY = "deploy the jar";
    static final String REMOVE_EARLIER = "remove the earlier deploy";
    static final String LIBS = "mirror the libraries into libs/";
    static final String RECORD = "record the deploy";
    static final String REMEMBER = "remember the workspace platform";

    /**
     * Checked first: where the jar goes (never over the agent jar) and that every file the deploy writes can be
     * written. Then: stop a leftover server, stop the server, deploy the new jar (whole, through a temp file), remove
     * an earlier deploy under another name (after the deploy, so a failed deploy leaves the earlier jar working),
     * mirror libs/, record the deploy, remember the platform, then the start's own steps.
     */
    private String deployAndRestart(Path project, Platform platform, ProjectBuilder.BuildResult build, boolean restart,
                                    int maxChars, Timings time) throws IOException, InterruptedException {
        if (build.jar() == null) {
            return "build ok in " + LogText.seconds(build.millis()) + " but no jar in " + project.resolve("build/libs")
                    + "; nothing deployed";
        }
        Path target = OutputFile.check(target(platform, build.jar(), project), "deploy target");
        Path libs = config.serverDir().resolve("libs");
        if (build.libs() != null) {
            OutputFile.checkFolder(libs, "libs folder");
        }
        OutputFile.check(deployedFile(), "deploy record");
        boolean remember = Platform.detect(project) != null && !Files.exists(Platform.file(config));
        if (remember) {
            OutputFile.check(Platform.file(config), "platform file");
        }
        if (build.libs() != null) {
            checkMirror(build.libs(), libs);
        }
        String shown = platform.addonDir() == null ? platform.serverJar() : platform.addonDir() + "/" + target.getFileName();
        Path earlier = platform.addonDir() == null ? null : earlierDeploy(platform, project, target.getParent());
        boolean wasRunning = server.running();
        StringBuilder out = new StringBuilder();
        if (restart && wasRunning && loaded(build.jar(), target)
                && (build.libs() == null || mirror(build.libs(), libs, true) == 0)) {
            return out.append("build ok in ").append(LogText.seconds(build.millis())).append(", jar unchanged: ")
                    .append(shown).append(" kept, server not restarted (server_stop then server_start restarts it)")
                    .append(run.resendPack()).toString();
        }
        Steps steps = new Steps("dev_cycle").then(STOP_LEFTOVER);
        boolean stopping = wasRunning && restart;
        if (stopping) {
            steps.then(STOP);
        }
        steps.then(DEPLOY);
        if (earlier != null) {
            steps.then(REMOVE_EARLIER);
        }
        if (build.libs() != null) {
            steps.then(LIBS);
        }
        steps.then(RECORD);
        if (remember) {
            steps.then(REMEMBER);
        }
        if (restart) {
            ServerRun.plan(steps);
        }
        String stopped = stopping ? "; the server is stopped: server_start starts it with what is deployed" : "";
        steps.ifFails(DEPLOY, "the earlier jar is still deployed" + stopped)
                .ifFails(REMOVE_EARLIER, "the new jar is deployed as " + shown + "; the earlier deploy may still be "
                        + "beside it as " + earlier + ": delete it, then dev_cycle (which deploys the jar again if that "
                        + "took it too)" + stopped)
                .ifFails(LIBS, "the jar is deployed, libs/ is partly updated: dev_cycle again mirrors it" + stopped)
                .ifFails(RECORD, "the jar is deployed; dev_cycle again records it" + stopped)
                .ifFails(REMEMBER, "the jar is deployed; the workspace platform is still " + server.platform().id()
                        + stopped);
        // Before deploying: a server an earlier core left running holds the old jar open.
        long t = System.nanoTime();
        String leftover = steps.note(STOP_LEFTOVER, server::stopLeftover);
        if (leftover != null) {
            out.append(leftover).append('\n');
            time.add("leftover stop", millisSince(t));
        }
        out.append("build ok in ").append(LogText.seconds(build.millis()));
        if (stopping) {
            t = System.nanoTime();
            steps.run(STOP, () -> server.stop(Duration.ofSeconds(30)));
            time.add("stop", millisSince(t));
        }
        steps.run(DEPLOY, () -> copy(build.jar(), target));
        String kept = earlier == null ? null
                : steps.note(REMOVE_EARLIER, () -> removeEarlier(earlier, target, sameFile(), () -> copy(build.jar(), target)));
        if (build.libs() != null) {
            steps.run(LIBS, () -> mirror(build.libs(), libs, false));
        }
        steps.run(RECORD, () -> rememberDeploy(project, target));
        if (remember) {
            steps.run(REMEMBER, () -> Platform.remember(config, platform));
        }
        out.append(", deployed ").append(build.jar().getFileName()).append(" as ").append(shown);
        if (kept != null && !kept.isEmpty()) {
            out.append(" (").append(kept).append(')');
        }
        if (!restart) {
            return out.append(wasRunning ? "; server still running the old jar until restarted" : "").toString();
        }
        if (server.serverJar() == null && config.serverCommand() == null) {
            return out.append("\nserver not set up, so nothing was started. Run server_setup, then dev_cycle.").toString();
        }
        ServerRun.Report report = run.startAndReport(steps, 180, maxChars);
        time.add("start", report.result().millis());
        if (report.result().agentMillis() >= 0) {
            time.add("agent", report.result().agentMillis());
        }
        return out.append('\n').append(report.text()).toString();
    }

    private static long millisSince(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000;
    }

    /** Where the jar goes: the addon folder, or for Minestom the server jar itself (the project is the server). */
    private Path target(Platform platform, Path jar, Path project) {
        if (platform.addonDir() == null) {
            return config.serverDir().resolve(platform.serverJar());
        }
        String name = deployName(jar, project) + ".jar";
        if (isAgentJar(platform, name)) {
            throw new ToolException("the project's jar deploys as " + platform.addonDir() + "/" + name
                    + ", which is MSC's agent jar on " + platform.displayName() + ": deploying it would replace the agent."
                    + " Rename the project (rootProject.name in settings.gradle.kts), then dev_cycle.");
        }
        return config.serverDir().resolve(platform.addonDir()).resolve(name);
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
                    // A folder is not a library, and deleting a full one fails every time: left alone.
                    if (!Files.isDirectory(old, LinkOption.NOFOLLOW_LINKS) && !keep.contains(old.getFileName().toString())
                            && !kept(old, dest, keep)) {
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

    /**
     * Refuses a mirror that would fail at every dev_cycle: a folder where a library jar goes cannot be replaced by it.
     */
    static void checkMirror(Path src, Path dest) throws IOException {
        if (!Files.isDirectory(dest)) {
            return;
        }
        try (var files = Files.list(src)) {
            for (Path from : files.toList()) {
                Path to = dest.resolve(from.getFileName().toString());
                if (Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ToolException("libs folder: " + to + " is a folder where the library "
                            + from.getFileName() + " goes. Remove it, then dev_cycle.");
                }
            }
        }
    }

    /**
     * True when {@code old} is the file of a jar {@code keep} names, under another spelling: on a case-insensitive
     * volume {@code Dep.jar} is {@code dep.jar}, and deleting it would delete the kept jar (#398).
     */
    private static boolean kept(Path old, Path dest, java.util.Set<String> keep) {
        return keep.stream().anyMatch(name -> AtomicFile.sameFile(old, dest.resolve(name)));
    }

    private static void copy(Path from, Path to) {
        try {
            OutputFile.copy(from, to);
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
            } catch (IllegalArgumentException malformed) {
                // A malformed unicode escape: unreadable, so no record. The deploy writes a fresh one, which otherwise
                // every dev_cycle would fail to read and to write (#405).
                return new Properties();
            }
        }
        return p;
    }

    /**
     * The jar an earlier dev_cycle deployed for this project, to delete after the deploy so the server never loads
     * two copies (Paper: ambiguous plugin name; Fabric: duplicate mod id). Without a record, the folder-named jar
     * that earlier cores deployed is the earlier one. Null when there is none to remove.
     */
    private Path earlierDeploy(Platform platform, Path project, Path addons) throws IOException {
        String recorded = deployed().getProperty(project.toAbsolutePath().normalize().toString());
        Path earlier;
        if (recorded != null) {
            earlier = config.serverDir().resolve(recorded).normalize();
        } else {
            Path folder = project.toAbsolutePath().normalize().getFileName();
            earlier = folder == null ? null : addons.resolve(folder + ".jar");
        }
        // Never the agent's jar: a project folder named msc-agent-paper, or a record from before #311, names it.
        if (earlier == null || !earlier.startsWith(addons) || isAgentJar(platform, earlier.getFileName().toString())) {
            return null;
        }
        return earlier;
    }

    /** For tests: how dev_cycle tells two names of one file apart, on this thread; null: the file system's answer. */
    private static final ThreadLocal<BiPredicate<Path, Path>> SAME_FILE = new ThreadLocal<>();

    static void sameFileForTests(BiPredicate<Path, Path> sameFile) {
        if (sameFile == null) {
            SAME_FILE.remove();
        } else {
            SAME_FILE.set(sameFile);
        }
    }

    private static BiPredicate<Path, Path> sameFile() {
        BiPredicate<Path, Path> test = SAME_FILE.get();
        return test != null ? test : AtomicFile::sameFile;
    }

    /** Puts the new jar in place again. */
    @FunctionalInterface
    interface Redeploy {
        void run() throws IOException;
    }

    /** The suffix {@code earlier} gets while it is set aside: not {@code .jar}, so no server loads it. */
    static final String ASIDE = ".msc-removing";

    /**
     * Removes {@code earlier} unless it is {@code target}'s file, and never loses {@code target} (#398, #405). The file
     * system's sameness answer ({@code sameFile}) is the quick check; where it is wrong (a volume that reports two
     * names of one file as two files), the removal itself is safe: {@code earlier} is first renamed aside, and if
     * {@code target} went with it they were one file, which is renamed back. Only then is the aside name deleted.
     * Returns null when nothing was there to remove, "" when it was removed, and a note when it was the new jar.
     * The call carries on in every case where the new jar is in place, so the deploy is recorded under
     * {@code target}'s name and the next dev_cycle does not meet {@code earlier} again.
     */
    static String removeEarlier(Path earlier, Path target, BiPredicate<Path, Path> sameFile, Redeploy redeploy)
            throws IOException {
        if (earlier.equals(target) || !Files.isRegularFile(earlier) || sameFile.test(earlier, target)) {
            return null;
        }
        Path aside = earlier.resolveSibling(earlier.getFileName() + ASIDE);
        try {
            Files.move(earlier, aside, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ToolException("cannot remove the earlier deploy " + earlier + " (" + Steps.reason(e)
                    + "). A running server holds the jar open: call server_stop, then dev_cycle.");
        }
        if (Files.isRegularFile(target)) {
            try {
                Files.delete(aside);
            } catch (IOException e) {
                throw new ToolException("the earlier deploy " + earlier + " is renamed to " + aside
                        + ", which no server loads, but cannot be deleted (" + Steps.reason(e) + "): delete it by hand");
            }
            return "";
        }
        // Renaming the earlier name took the new jar with it: one file under two names.
        try {
            Files.move(aside, target);
        } catch (IOException e) {
            redeploy.run();
            try {
                Files.deleteIfExists(aside);
            } catch (IOException ignored) {
                // Left as a name no server loads; the jar itself is in place again.
            }
        }
        if (!Files.isRegularFile(target)) {
            throw new ToolException("removing the earlier deploy " + earlier + " also took the jar just deployed as "
                    + target + ", and it could not be put back: dev_cycle deploys it again");
        }
        return earlier + " is the new jar under another name on this volume, so it was kept";
    }

    private void rememberDeploy(Path project, Path target) throws IOException {
        Properties p = deployed();
        p.setProperty(project.toAbsolutePath().normalize().toString(),
                config.serverDir().relativize(target).toString().replace('\\', '/'));
        OutputFile.write(deployedFile(), to -> {
            try (OutputStream out = Files.newOutputStream(to)) {
                p.store(out, "jar dev_cycle last deployed per project");
            }
        });
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
