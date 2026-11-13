package io.github.imnotstable.msc.agent.minestom;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.agent.common.SlicedJob;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minestom.server.MinecraftServer;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

/** Calls come from a virtual thread, as op handlers do, while the test thread ticks the server. */
@EnvTest
class SchedulerMainThreadTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static SchedulerMainThread main(Env env, Duration timeout) {
        return new SchedulerMainThread(env.process().scheduler(), Duration.ofMillis(20), timeout);
    }

    private static <T> CompletableFuture<T> onVirtualThread(Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static Throwable failure(CompletableFuture<?> future) throws Exception {
        try {
            future.get(0, TimeUnit.SECONDS);
            throw new AssertionError("expected a failure");
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    /** A job that finishes on its {@code steps}-th step, or throws on its first when {@code steps} is 0. */
    private static final class CountingJob implements SlicedJob<String> {
        final AtomicInteger stepped = new AtomicInteger();
        private final int steps;

        CountingJob(int steps) {
            this.steps = steps;
        }

        @Override
        public boolean step(long deadlineNanos) {
            int n = stepped.incrementAndGet();
            if (steps == 0) {
                throw new IllegalStateException("job broke");
            }
            return n >= steps;
        }

        @Override
        public String result() {
            return "done after " + stepped.get();
        }
    }

    @Test
    void aJobGetsOneStepPerTickUntilItFinishes(Env env) throws Exception {
        CountingJob job = new CountingJob(3);
        CompletableFuture<String> result = onVirtualThread(() -> main(env, TIMEOUT).run(job));

        env.tickWhile(() -> !result.isDone(), TIMEOUT);
        for (int i = 0; i < 3; i++) {
            env.tick();
        }

        assertThat(result.get(0, TimeUnit.SECONDS)).isEqualTo("done after 3");
        assertThat(job.stepped).hasValue(3);
    }

    @Test
    void aJobAfterTheTaskStoppedStartsItAgain(Env env) throws Exception {
        SchedulerMainThread main = main(env, TIMEOUT);
        for (int round = 1; round <= 2; round++) {
            CountingJob job = new CountingJob(2);
            CompletableFuture<String> result = onVirtualThread(() -> main.run(job));
            env.tickWhile(() -> !result.isDone(), TIMEOUT);
            env.tick();

            assertThat(result.get(0, TimeUnit.SECONDS)).as("round %d", round).isEqualTo("done after 2");
            assertThat(main.queued()).isZero();
        }
    }

    /** Finishes on its second step and records the deadline of each step. */
    private static final class DeadlineJob implements SlicedJob<Integer> {
        final List<Long> deadlines = new CopyOnWriteArrayList<>();

        @Override
        public boolean step(long deadlineNanos) {
            deadlines.add(deadlineNanos);
            return deadlines.size() == 2;
        }

        @Override
        public Integer result() {
            return deadlines.size();
        }
    }

    @Test
    void concurrentJobsShareOneBudgetPerTick(Env env) throws Exception {
        SchedulerMainThread main = main(env, TIMEOUT);
        DeadlineJob first = new DeadlineJob();
        DeadlineJob second = new DeadlineJob();
        CompletableFuture<Integer> a = onVirtualThread(() -> main.run(first));
        CompletableFuture<Integer> b = onVirtualThread(() -> main.run(second));
        long end = System.nanoTime() + TIMEOUT.toNanos();
        while (main.queued() < 2) {
            assertThat(System.nanoTime()).as("jobs queued in time").isLessThan(end);
            Thread.sleep(1);
        }

        env.tickWhile(() -> !a.isDone() || !b.isDone(), TIMEOUT);

        assertThat(a.get(0, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(b.get(0, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(second.deadlines).hasSize(2).isEqualTo(first.deadlines);
    }

    @Test
    void errorsReachTheCallerAndStopTheTask(Env env) throws Exception {
        SchedulerMainThread main = main(env, TIMEOUT);
        CountingJob job = new CountingJob(0);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> jobResult = onVirtualThread(() -> main.run(job));
        CompletableFuture<String> callResult = onVirtualThread(() -> main.call(() -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("call broke");
        }));

        env.tickWhile(() -> !jobResult.isDone() || !callResult.isDone(), TIMEOUT);
        for (int i = 0; i < 3; i++) {
            env.tick();
        }

        assertThat(failure(jobResult)).isInstanceOf(IllegalStateException.class).hasMessage("job broke");
        assertThat(failure(callResult)).isInstanceOf(IllegalArgumentException.class).hasMessage("call broke");
        assertThat(job.stepped).hasValue(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void aTimedOutJobIsNeverStepped(Env env) throws Exception {
        CountingJob job = new CountingJob(1);
        AtomicInteger calls = new AtomicInteger();
        SchedulerMainThread main = main(env, Duration.ofMillis(50));
        CompletableFuture<String> jobResult = onVirtualThread(() -> main.run(job));
        CompletableFuture<Integer> callResult = onVirtualThread(() -> main.call(calls::incrementAndGet));

        CompletableFuture.allOf(jobResult, callResult).handle((r, t) -> null).get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        for (int i = 0; i < 3; i++) {
            env.tick();
        }

        assertThat(failure(jobResult)).isInstanceOfSatisfying(OpException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.TIMEOUT));
        assertThat(failure(callResult)).isInstanceOf(OpException.class);
        assertThat(job.stepped).hasValue(0);
        assertThat(calls).hasValue(0);
    }

    @Test
    void onATickThreadWorkRunsInline(Env env) throws Exception {
        SchedulerMainThread main = main(env, TIMEOUT);
        CompletableFuture<String> result = new CompletableFuture<>();
        Thread.ofPlatform().name(MinecraftServer.THREAD_NAME_TICK + "-test").start(() -> {
            try {
                result.complete(main.call(() -> "called") + ", " + main.run(new CountingJob(2)));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });

        assertThat(result.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("called, done after 2");
    }
}
