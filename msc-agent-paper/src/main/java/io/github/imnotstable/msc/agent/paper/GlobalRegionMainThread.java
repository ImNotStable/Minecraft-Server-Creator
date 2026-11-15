package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.SlicedJob;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * {@link MainThread} on Folia, which has no main thread and refuses Bukkit's scheduler: server-wide work (info,
 * console commands, packs) runs on the global region thread, as Folia runs console commands. World edits would have to
 * be split by the region that owns each chunk, so this offers none: {@link #run} refuses, and on Folia the agent does
 * not route the world operations at all (ADR 0045).
 */
final class GlobalRegionMainThread implements MainThread {
    private final Executor global;
    private final BooleanSupplier onGlobalThread;
    private final Duration timeout;

    /**
     * @param global         runs a task on the global region thread ({@code Bukkit.getGlobalRegionScheduler().execute})
     * @param onGlobalThread true on that thread ({@code Bukkit.isGlobalTickThread})
     */
    GlobalRegionMainThread(Executor global, BooleanSupplier onGlobalThread, Duration timeout) {
        this.global = global;
        this.onGlobalThread = onGlobalThread;
        this.timeout = timeout;
    }

    @Override
    public <T> T call(Supplier<T> task) {
        if (onGlobalThread.getAsBoolean()) {
            return task.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            global.execute(() -> {
                try {
                    future.complete(task.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new OpException(ErrorCodes.SHUTDOWN, "server is shutting down");
        }
        return MainThread.await(future, timeout);
    }

    @Override
    public <T> T run(SlicedJob<T> job) {
        throw new OpException(ErrorCodes.UNKNOWN_OP, "world operations are not supported on Folia");
    }
}
