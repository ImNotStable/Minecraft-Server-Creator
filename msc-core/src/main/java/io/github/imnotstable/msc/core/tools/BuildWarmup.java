package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.server.ProjectBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * A first build started right after {@code project_scaffold} for templates whose first build is slow (ForgeGradle and
 * ModDevGradle download, decompile and recompile Minecraft; NeoForge's test run downloads assets), so it runs while
 * the AI writes code instead of inside the first {@code dev_cycle}. {@code dev_cycle} waits for a warmup of its
 * project before building, so two Gradle builds never race for the same caches. See ADR 0028.
 */
final class BuildWarmup {
    /** ForgeGradle's first setup took 502 s on a dev PC; leave room. */
    static final Duration TIMEOUT = Duration.ofMinutes(20);

    private final ProjectBuilder builder;
    private final BackgroundWork background;
    private final Map<Path, CompletableFuture<ProjectBuilder.BuildResult>> running = new ConcurrentHashMap<>();

    /** Warmups run on {@code background}, so closing the workspace stops their Gradle builds. */
    BuildWarmup(ProjectBuilder builder, BackgroundWork background) {
        this.builder = builder;
        this.background = background;
    }

    /** Start building {@code project} in the background: the dev jar and the tests, as dev_cycle will. */
    void start(Path project) {
        CompletableFuture<ProjectBuilder.BuildResult> f = new CompletableFuture<>();
        background.start("msc-build-warmup", () -> {
            try {
                f.complete(builder.warm(project, TIMEOUT));
            } catch (IOException | RuntimeException e) {
                f.completeExceptionally(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                f.completeExceptionally(e);
            }
        });
        running.put(key(project), f);
    }

    /**
     * Wait for the project's warmup if one was started, and forget it once it ended. A waiter that is interrupted
     * leaves it known, so the next dev_cycle still waits instead of building beside it.
     *
     * @return how long this call waited, in milliseconds, or -1 when there was no warmup
     */
    long await(Path project) throws InterruptedException {
        Path key = key(project);
        CompletableFuture<ProjectBuilder.BuildResult> f = running.get(key);
        if (f == null) {
            return -1;
        }
        long start = System.nanoTime();
        try {
            f.get();
        } catch (ExecutionException e) {
            // A failed warmup is only a cold cache: dev_cycle's own build reports the real error.
        }
        running.remove(key, f);
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static Path key(Path project) {
        return project.toAbsolutePath().normalize();
    }
}
