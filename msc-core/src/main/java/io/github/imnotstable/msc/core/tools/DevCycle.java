package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.process.AtomicFile;
import io.github.imnotstable.msc.core.server.LogCondenser;
import io.github.imnotstable.msc.core.server.LoaderInstaller;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ProjectBuilder;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.server.WorkspaceProjects;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        Path project = args.path("project", ".", config.inWorkspace());
        if (!Files.isDirectory(project)) {
            // Checked first: a missing project is refused before the build's init script is written.
            throw new ToolException("no project at " + project + ". Give the project folder, relative to the workspace.");
        }
        // Checked first: the deploy would replace the jars under another live session's server (ADR 0037).
        server.checkNoOtherSession();
        boolean restart = args.bool("restart", true);
        Timings time = new Timings();
        checkServed(project);
        Platform platform = platform(project);
        if (restart) {
            checkStart(project, platform);
        }
        long warmed = warmup.await(project);
        if (warmed >= 0) {
            time.add("waited for first build", warmed);
        }
        ProjectBuilder.BuildResult jar = builder.devJar(project, BUILD_TIMEOUT, platform.addonDir() == null);
        time.add("jar", jar.millis());
        if (!jar.ok()) {
            return new Budget.Parts().add("BUILD FAILED in " + LogText.seconds(jar.millis()) + "\n")
                    .cut(n -> LogCondenser.condenseBuild(jar.output(), n)).text(maxChars, null,
                            hint(maxChars, null, false));
        }
        long checkStarted = System.currentTimeMillis();
        Check tests = check(project);
        long logMark = server.log().mark();
        Budget.Parts out = new Budget.Parts();
        Deployed rest;
        ProjectBuilder.BuildResult checked;
        try {
            rest = deployAndRestart(project, platform, jar, restart, time);
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
            // Only a jar this call deployed is "deployed anyway": an unchanged one was kept, as its line says (#668).
            out.add("TESTS FAILED in " + LogText.seconds(checked.millis())
                    + (rest.deployed() ? " (the new jar is deployed anyway)" : "") + "\n")
                    .cut(n -> LogCondenser.condenseBuild(checked.output(), n)).add("\n");
        }
        // One budget (#473): the time line is kept last, the test output and the startup log share what is left.
        String results = checked.ok() ? null : testResults(project, checkStarted);
        boolean started = restart && !server.log().since(logMark).isEmpty();
        return out.add(rest.parts()).text(maxChars, time.line(), hint(maxChars, results, started));
    }

    /**
     * The hint of a cut that is not a part's own (#620): of dev_cycle's own lines, or the line counting the outputs
     * cut. It names only what really shows what this result held: the test results Gradle wrote on this run
     * ({@code results}, or null), and server_logs for a startup log ({@code started}). dev_cycle's own lines are in no
     * file or log, so beyond those only a larger maxChars shows them.
     */
    static String hint(int maxChars, String results, boolean started) {
        List<String> where = new ArrayList<>();
        if (results != null) {
            where.add("Failed tests: " + results + ".");
        }
        if (started) {
            where.add("Startup log: server_logs.");
        }
        String hint = Budget.raise(maxChars, String.join(" ", where));
        return hint.isEmpty() ? "No other tool shows these lines." : hint;
    }

    /**
     * Where the failed tests are, as the hint names it: Gradle's {@code build/test-results} of {@code project}, shown
     * relative to the workspace, when this run's check wrote a file there (written since {@code since}, give or take
     * a coarse file clock); else null, as a failure before any test ran writes none and older files are stale.
     */
    private String testResults(Path project, long since) {
        Path dir = project.resolve("build").resolve("test-results");
        long from = since / 1000 * 1000 - 1000;
        try (var files = Files.walk(dir, 3)) {
            boolean fresh = files.anyMatch(f -> {
                try {
                    return Files.isRegularFile(f) && Files.getLastModifiedTime(f).toMillis() >= from;
                } catch (IOException e) {
                    return false;
                }
            });
            if (!fresh) {
                return null;
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            return null;
        }
        Path workspace = config.workspace().toAbsolutePath().normalize();
        Path shown = dir.toAbsolutePath().normalize();
        return (shown.startsWith(workspace) ? workspace.relativize(shown) : shown).toString().replace('\\', '/');
    }

    /**
     * Check first: a start that would refuse after the deploy (the EULA not accepted) refuses now, before the build
     * and any change, so the refusal is the plain one-line answer and the server is not stopped for nothing. Without
     * a server jar nothing starts after the deploy, which the result says: no refusal.
     */
    private void checkStart(Path project, Platform platform) {
        // The deploy remembers the project's platform for a workspace that has none; the start then uses it.
        boolean remember = Platform.detect(project) != null && !Files.exists(Platform.file(config));
        Platform starts = remember ? platform : server.platform();
        if (Files.exists(server().resolve(starts.serverJar()))) {
            run.checkStart(starts, false);
        }
    }

    /**
     * Check first: a project the workspace's server does not run (another agent family than every platform the
     * project runs on, {@link Platform#runsOn}) is refused before the build, not deployed where that server never
     * loads it with an OK (#636, #642, ADR 0048).
     */
    private void checkServed(Path project) {
        List<Platform> runsOn = Platform.runsOn(project);
        Platform runs = runsOn.isEmpty() ? null : Platform.otherFamily(config, runsOn);
        if (runs == null) {
            return;
        }
        Platform own = runsOn.getFirst();
        Path workspace = config.workspace().toAbsolutePath().normalize();
        Path shown = project.toAbsolutePath().normalize();
        String name = shown.equals(workspace) ? "the workspace's project"
                : (shown.startsWith(workspace) ? workspace.relativize(shown) : shown).toString().replace('\\', '/');
        throw new ToolException(name + " is a " + own.displayName() + " project, but this workspace runs "
                + runs.displayName() + ", which never loads it; nothing was built or deployed. server_setup platform="
                + own.id() + " switches the workspace to " + own.displayName() + " (" + runs.displayName()
                + " projects then need server_setup platform=" + runs.id() + "), or msc_workspace gives the project "
                + "its own workspace.");
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
     * The workspace's platform for a project that names none; else the one that serves the project's
     * ({@link Platform#serving}, of every platform it runs on): its own when the workspace has none yet (the deploy
     * remembers it).
     */
    private Platform platform(Path project) {
        List<Platform> runsOn = Platform.runsOn(project);
        return runsOn.isEmpty() ? server.platform() : Platform.serving(config, runsOn);
    }

    static final String STOP_LEFTOVER = "stop a server an earlier session left holding the jar";
    static final String STOP = "stop the server";
    static final String SWEEP = "remove what an interrupted earlier call left";
    static final String DEPLOY = "deploy the jar";
    static final String REMOVE_EARLIER = "remove the earlier deploy";
    static final String LIBS = "mirror the libraries into libs/";
    static final String RECORD = "record the deploy";
    static final String REMEMBER = "remember the workspace platform";

    /**
     * Checked first: where the jar goes (never over the agent jar) and that every file the deploy writes can be
     * written; the start's own refusals were checked before the build ({@link #checkStart}). Then: stop a leftover
     * server, stop the server, remove what interrupted calls left (housekeeping), deploy the new jar (whole, through a
     * temp file), remove an earlier deploy under another name (after the deploy, so a failed deploy leaves the earlier
     * jar working), mirror libs/, record the deploy, remember the platform, then the start's own steps.
     */
    private Deployed deployAndRestart(Path project, Platform platform, ProjectBuilder.BuildResult build,
                                      boolean restart, Timings time) throws IOException, InterruptedException {
        // Every way that leaves a running server running sends it a changed pack, whatever happens to the jar (#690).
        if (build.jar() == null) {
            return Deployed.not(new Budget.Parts().add("build ok in " + LogText.seconds(build.millis()) + " but no jar in " + project.resolve("build/libs")
                    + "; nothing deployed" + (server.running() ? run.packIfChanged() : "")));
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
        DeployRecords records = DeployRecords.read(deployedFile());
        String unreadable = records.note(deployedFile());
        Path earlier = platform.addonDir() == null ? null : earlierDeploy(platform, project, target.getParent(), records);
        boolean wasRunning = server.running();
        StringBuilder out = new StringBuilder(unreadable == null ? "" : unreadable + "\n");
        // Whether or not this call restarts: a jar the running server already loaded is kept, not copied again with a
        // new time that makes the next dev_cycle restart for nothing, and a changed pack is still sent (#681).
        if (wasRunning && loaded(build.jar(), target)
                && (build.libs() == null || mirror(build.libs(), libs, server(), true) == 0)) {
            try {
                ScanFolders.sweep(server(), ScanFolders.all(server()));
            } catch (IOException e) {
                // Best effort: the next dev_cycle that deploys sweeps again, as a step that reports a failure.
            }
            return Deployed.not(new Budget.Parts().add(out.append("build ok in ").append(LogText.seconds(build.millis()))
                    .append(", jar unchanged: ").append(shown)
                    .append(" kept, server not restarted (server_stop then server_start restarts it)")
                    .append(run.packIfChanged()).toString()));
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
        // Before deploying: a leftover aside copy of a jar is a second copy of the mod on Quilt (#417).
        List<Path> swept = new ArrayList<>();
        String unswept = steps.optional(SWEEP, () -> {
            swept.addAll(ScanFolders.sweep(server(), ScanFolders.all(server())));
            swept.addAll(ScanFolders.sweepTemps(deployedFile().getParent(), deployedFile().getFileName().toString()));
        });
        String[] claimed = new String[1];
        steps.run(DEPLOY, () -> {
            if (platform.addonDir() == null) {
                // The project is the server jar: a server.jar the marker does not vouch for as Minestom's is removed
                // before the copy, not left to look like this one's (#518, #524).
                claimed[0] = LoaderInstaller.claimSharedJar(target.getParent(), ServerSetup.MINESTOM_ID);
                if (claimed[0] != null) {
                    steps.ifFails(DEPLOY, LoaderInstaller.removedLine(claimed[0], "no jar is deployed: dev_cycle again")
                            .strip() + stopped);
                } else if (!Files.exists(target)) {
                    steps.ifFails(DEPLOY, "no jar is deployed: dev_cycle again" + stopped);
                }
            }
            copy(build.jar(), target, server());
            if (platform.addonDir() == null) {
                // Only after the copy, so the marker never vouches for a missing or other jar.
                steps.ifFails(DEPLOY, "the jar is deployed, but the folder does not record it as Minestom's yet: "
                        + "dev_cycle again" + stopped);
                LoaderInstaller.markSharedJar(target.getParent(), ServerSetup.MINESTOM_ID);
            }
        });
        String kept = earlier == null ? null
                : steps.note(REMOVE_EARLIER, () -> removeEarlier(earlier, target, server(), sameFile(),
                        () -> copy(build.jar(), target, server())));
        if (build.libs() != null) {
            steps.run(LIBS, () -> mirror(build.libs(), libs, server(), false));
        }
        steps.run(RECORD, () -> rememberDeploy(project, target));
        if (remember) {
            steps.run(REMEMBER, () -> Platform.remember(config, platform));
        }
        out.append(", deployed ").append(build.jar().getFileName()).append(" as ").append(shown);
        if (kept != null && !kept.isEmpty()) {
            out.append(" (").append(kept).append(')');
        }
        if (!swept.isEmpty()) {
            out.append("; removed ").append(swept.size()).append(swept.size() == 1 ? " file" : " files")
                    .append(" an interrupted earlier call left");
        }
        out.append(unswept);
        if (claimed[0] != null) {
            out.append('\n').append(LoaderInstaller.removedLine(claimed[0], "the project's jar replaces it").strip());
        }
        if (!restart) {
            return new Deployed(new Budget.Parts().add(out.append(wasRunning
                    ? "; server still running the old jar until restarted" + run.packIfChanged() : "").toString()), true);
        }
        if (server.serverJar() == null && config.serverCommand() == null) {
            return new Deployed(new Budget.Parts().add(
                    out.append("\nserver not set up, so nothing was started. Run server_setup, then ")
                            .append(WorkspaceProjects.devCycle(config, project)).append(".").toString()), true);
        }
        ServerRun.Report report = run.startAndReport(steps, false, 180);
        time.add("start", report.result().millis());
        if (report.result().agentMillis() >= 0) {
            time.add("agent", report.result().agentMillis());
        }
        return new Deployed(new Budget.Parts().add(out.append('\n').toString()).add(report.parts()), true);
    }

    /** What the deploy step says, and whether it deployed the built jar (not when there was none or it was unchanged). */
    private record Deployed(Budget.Parts parts, boolean deployed) {
        static Deployed not(Budget.Parts parts) {
            return new Deployed(parts, false);
        }
    }

    private Path server() {
        return config.serverDir();
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
                    + " Rename the project (rootProject.name in settings.gradle.kts), then "
                    + WorkspaceProjects.devCycle(config, project) + ".");
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
    static int mirror(Path src, Path dest, Path serverDir, boolean dryRun) throws IOException {
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
                    copy(from, to, serverDir);
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

    /** Through a temp file outside the scan folders ({@link ScanFolders#copy}): even a temp file there may load. */
    private static void copy(Path from, Path to, Path serverDir) {
        try {
            ScanFolders.copy(from, to, serverDir);
        } catch (IOException e) {
            throw new ToolException("cannot replace " + to + " (" + e.getMessage()
                    + "). A running server holds the jar open: call server_stop, then dev_cycle.");
        }
    }

    /** {@code .msc/deployed.properties}: project folder to the jar dev_cycle last deployed for it. */
    private Path deployedFile() {
        return config.dataDir().resolve("deployed.properties");
    }

    /**
     * The jar an earlier dev_cycle deployed for this project, to delete after the deploy so the server never loads
     * two copies (Paper: ambiguous plugin name; Fabric: duplicate mod id). Without a record, the folder-named jar
     * that earlier cores deployed is the earlier one. Null when there is none to remove.
     */
    private Path earlierDeploy(Platform platform, Path project, Path addons, DeployRecords records) {
        String recorded = records.get(project.toAbsolutePath().normalize().toString());
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

    /**
     * Removes {@code earlier} unless it is {@code target}'s file, and never loses {@code target} (#398, #405). The file
     * system's sameness answer ({@code sameFile}) is the quick check; where it is wrong (a volume that reports two
     * names of one file as two files), the removal itself is safe: {@code earlier} is first renamed aside, and if
     * {@code target} went with it they were one file, which is renamed back. Only then is the aside file deleted. The
     * aside place is the server folder's staging folder, which no server scans (#417: Quilt loads any name in
     * {@code mods/} that does not start with a dot); a crash there leaves nothing that loads, and the next dev_cycle
     * sweeps it. Returns null when nothing was there to remove, "" when it was removed, and a note when it was the new
     * jar. The call carries on in every case where the new jar is in place, so the deploy is recorded under
     * {@code target}'s name and the next dev_cycle does not meet {@code earlier} again.
     */
    static String removeEarlier(Path earlier, Path target, Path serverDir, BiPredicate<Path, Path> sameFile,
                                Redeploy redeploy) throws IOException {
        if (earlier.equals(target) || !Files.isRegularFile(earlier) || sameFile.test(earlier, target)) {
            return null;
        }
        Path aside = setAside(earlier, serverDir);
        Steps.killPoint("set aside " + earlier.getFileName());
        if (Files.isRegularFile(target)) {
            try {
                Files.delete(aside);
            } catch (IOException e) {
                throw new ToolException("the earlier deploy " + earlier + " is moved to " + aside
                        + ", where no server loads it, but cannot be deleted (" + Steps.reason(e)
                        + "); the next dev_cycle removes it");
            }
            Steps.killPoint("deleted " + aside.getFileName());
            return "";
        }
        // Renaming the earlier name took the new jar with it: one file under two names.
        try {
            move(aside, target);
        } catch (IOException e) {
            redeploy.run();
            try {
                Files.deleteIfExists(aside);
            } catch (IOException ignored) {
                // Left where no server loads it; the next dev_cycle sweeps it. The jar itself is in place again.
            }
        }
        Steps.killPoint("put back " + target.getFileName());
        if (!Files.isRegularFile(target)) {
            throw new ToolException("removing the earlier deploy " + earlier + " also took the jar just deployed as "
                    + target + ", and it could not be put back: dev_cycle deploys it again");
        }
        return earlier + " is the new jar under another name on this volume, so it was kept";
    }

    /**
     * Renames {@code earlier} into the staging folder; where that is on another volume, to a dot name beside it that
     * no loader loads ({@link ScanFolders.Loader}). Returns where it went.
     */
    private static Path setAside(Path earlier, Path serverDir) {
        Path aside = ScanFolders.aside(earlier, serverDir, false);
        try {
            try {
                Files.createDirectories(aside.getParent());
                Files.deleteIfExists(aside);
                return move(earlier, aside);
            } catch (AtomicMoveNotSupportedException e) {
                aside = ScanFolders.aside(earlier, serverDir, true);
                return Files.move(earlier, aside, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ToolException("cannot remove the earlier deploy " + earlier + " (" + Steps.reason(e)
                    + "). A running server holds the jar open: call server_stop, then dev_cycle.");
        }
    }

    /** A rename, never a copy: across volumes it fails ({@link AtomicMoveNotSupportedException}) instead. */
    private static Path move(Path from, Path to) throws IOException {
        return Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Records {@code target} for this project. Read again, not from the start of the call: only this project's line
     * changes, every other line of the file stays as it is now, an unreadable one included (#418).
     */
    private void rememberDeploy(Path project, Path target) throws IOException {
        String text = DeployRecords.read(deployedFile()).with(project.toAbsolutePath().normalize().toString(),
                config.serverDir().relativize(target).toString().replace('\\', '/'));
        OutputFile.write(deployedFile(), to -> {
            Steps.killPoint("record temp created");
            Files.writeString(to, text, StandardCharsets.ISO_8859_1);
            Steps.killPoint("record temp written");
        });
        Steps.killPoint("record moved into place");
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
