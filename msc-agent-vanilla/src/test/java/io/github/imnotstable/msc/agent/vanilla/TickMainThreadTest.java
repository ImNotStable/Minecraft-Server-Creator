package io.github.imnotstable.msc.agent.vanilla;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.agent.common.SlicedJob;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TickMainThreadTest {
    /** A job that needs {@code steps} ticks, one step per tick. */
    private static final class CountingJob implements SlicedJob<Integer> {
        private final int steps;
        private int done;

        CountingJob(int steps) {
            this.steps = steps;
        }

        @Override
        public boolean step(long deadlineNanos) {
            return ++done == steps;
        }

        @Override
        public Integer result() {
            return done;
        }
    }

    private final TickMainThread main = new TickMainThread(() -> false, Duration.ofMillis(20), Duration.ofSeconds(5));

    /** Waits until {@code count} jobs are queued, so a tick is sure to see them. */
    private void awaitQueued(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (main.queued() < count) {
            assertThat(System.nanoTime()).as("jobs queued in time").isLessThan(deadline);
            Thread.sleep(1);
        }
    }

    @Test
    void jobsRunInSlicesAcrossTicksAndQuickCallsDoNotWaitBehindThem() throws Exception {
        CompletableFuture<Integer> job = CompletableFuture.supplyAsync(() -> main.run(new CountingJob(3)));
        awaitQueued(1);
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() -> main.call(() -> "info"));
        awaitQueued(2);

        main.tick();
        assertThat(call.get(1, TimeUnit.SECONDS)).isEqualTo("info");
        assertThat(job).isNotDone();
        main.tick();
        main.tick();
        assertThat(job.get(1, TimeUnit.SECONDS)).isEqualTo(3);
    }

    @Test
    void errorsInAJobReachTheCaller() throws Exception {
        CompletableFuture<Object> call = CompletableFuture.supplyAsync(() -> main.call(() -> {
            throw new IllegalArgumentException("bad block");
        }));
        awaitQueued(1);
        main.tick();

        assertThat(call).failsWithin(Duration.ofSeconds(1))
                .withThrowableThat().havingRootCause().isInstanceOf(IllegalArgumentException.class)
                .withMessage("bad block");
    }

    @Test
    void stoppingFailsQueuedAndNewJobsWithShutdown() throws Exception {
        CompletableFuture<Integer> job = CompletableFuture.supplyAsync(() -> main.run(new CountingJob(5)));
        awaitQueued(1);
        main.stop();

        assertThat(job).failsWithin(Duration.ofSeconds(1)).withThrowableThat().havingRootCause()
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.SHUTDOWN));
        CompletableFuture<String> late = CompletableFuture.supplyAsync(() -> main.call(() -> "x"));
        assertThat(late).failsWithin(Duration.ofSeconds(1)).withThrowableThat().havingRootCause()
                .isInstanceOf(OpException.class);
    }

    @Test
    void timesOutWhenTheServerNeverTicks() {
        TickMainThread stalled = new TickMainThread(() -> false, Duration.ofMillis(20), Duration.ofMillis(100));
        CompletableFuture<String> call = CompletableFuture.supplyAsync(() -> stalled.call(() -> "x"));

        assertThat(call).failsWithin(Duration.ofSeconds(2)).withThrowableThat().havingRootCause()
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.TIMEOUT));
    }
}
