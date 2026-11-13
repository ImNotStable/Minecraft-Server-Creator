package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.Region;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JobQueueTest {
    private static final Duration BUDGET = Duration.ofMillis(20);

    /** Works until the deadline on every step and records the deadlines it was given. */
    private static final class GreedyJob implements SlicedJob<Integer> {
        final List<Long> deadlines = new ArrayList<>();
        private final int steps;

        GreedyJob(int steps) {
            this.steps = steps;
        }

        @Override
        public boolean step(long deadlineNanos) {
            deadlines.add(deadlineNanos);
            while (System.nanoTime() < deadlineNanos) {
                Thread.onSpinWait();
            }
            return deadlines.size() == steps;
        }

        @Override
        public Integer result() {
            return deadlines.size();
        }
    }

    @Test
    void concurrentJobsShareOneBudgetPerTick() {
        JobQueue queue = new JobQueue();
        GreedyJob first = new GreedyJob(2);
        GreedyJob second = new GreedyJob(2);
        CompletableFuture<Integer> a = queue.add(first);
        CompletableFuture<Integer> b = queue.add(second);

        long start = System.nanoTime();
        queue.step(BUDGET);
        long spent = System.nanoTime() - start;

        assertThat(second.deadlines).isEqualTo(first.deadlines);
        assertThat(spent).isLessThan(BUDGET.toNanos() * 3 / 2);
        queue.step(BUDGET);
        assertThat(a).isCompletedWithValue(2);
        assertThat(b).isCompletedWithValue(2);
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void aReadQueuedBehindAJobThatUsesTheWholeBudgetFinishesWithinTwoTicks() {
        MapWorld world = new MapWorld();
        world.entities.add(new Ops.EntitySummary("minecraft:pig", 0.5, 70, 0.5, null));
        Region one = Region.of("world", 0, 70, 0, 0, 70, 0);
        JobQueue queue = new JobQueue();
        CompletableFuture<Integer> hog = queue.add(new GreedyJob(10));
        CompletableFuture<Ops.ReadResult> plain = queue.add(new WorldReadJob<>(world, new Ops.ReadParams(one, false, false)));
        CompletableFuture<Ops.ReadResult> extras = queue.add(new WorldReadJob<>(world, new Ops.ReadParams(one, true, true)));
        Duration budget = Duration.ofMillis(1);

        queue.step(budget);
        assertThat(plain).isCompleted();
        assertThat(extras).isNotDone();
        queue.step(budget);
        assertThat(extras).isCompleted();
        assertThat(extras.join().entities()).hasSize(1);
        assertThat(hog).isNotDone();
    }

    @Test
    void errorsReachTheJobsFutureAndRemoveIt() {
        JobQueue queue = new JobQueue();
        CompletableFuture<Object> failed = queue.add(new SlicedJob<>() {
            @Override
            public boolean step(long deadlineNanos) {
                throw new IllegalArgumentException("bad block");
            }

            @Override
            public Object result() {
                return null;
            }
        });

        queue.step(BUDGET);

        assertThat(failed).isCompletedExceptionally();
        assertThat(queue.size()).isZero();
    }

    @Test
    void stopFailsQueuedJobsAndRefusesNewOnes() {
        JobQueue queue = new JobQueue();
        CompletableFuture<Integer> queued = queue.add(new GreedyJob(5));

        queue.stop();

        assertThat(queued).isCompletedExceptionally();
        assertThatThrownBy(() -> queue.add(new GreedyJob(1))).isInstanceOfSatisfying(OpException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCodes.SHUTDOWN));
    }

    @Test
    void theDriverIsHeldWhileJobsWaitAndFreedWhenTheQueueEmpties() {
        JobQueue queue = new JobQueue();
        CompletableFuture<Integer> job = queue.add(new GreedyJob(2));

        assertThat(queue.claimDriver()).isTrue();
        assertThat(queue.claimDriver()).isFalse();
        assertThat(queue.stepAndKeepDriving(Duration.ZERO)).isTrue();
        assertThat(queue.stepAndKeepDriving(Duration.ZERO)).isFalse();
        assertThat(job).isCompletedWithValue(2);
        assertThat(queue.claimDriver()).isTrue();
    }

    /** Races an add against the driver stopping; a lost wakeup leaves the added job unstepped. */
    @Test
    void aJobAddedWhileTheDriverStopsIsStillStepped() throws InterruptedException {
        for (int i = 0; i < 2_000; i++) {
            JobQueue queue = new JobQueue();
            CompletableFuture<Integer> first = queue.add(new GreedyJob(1));
            assertThat(queue.claimDriver()).isTrue();
            AtomicReference<CompletableFuture<Integer>> second = new AtomicReference<>();
            CountDownLatch go = new CountDownLatch(1);
            Thread driver = Thread.ofVirtual().start(() -> {
                awaitQuietly(go);
                drive(queue);
            });
            Thread adder = Thread.ofVirtual().start(() -> {
                awaitQuietly(go);
                second.set(queue.add(new GreedyJob(1)));
                if (queue.claimDriver()) {
                    drive(queue);
                }
            });
            go.countDown();
            driver.join();
            adder.join();

            assertThat(first).isCompletedWithValue(1);
            assertThat(second.get()).as("job added in round %d", i).isCompletedWithValue(1);
            assertThat(queue.claimDriver()).isTrue();
        }
    }

    private static void drive(JobQueue queue) {
        while (queue.stepAndKeepDriving(Duration.ZERO)) {
            Thread.onSpinWait();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
