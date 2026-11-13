package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
}
