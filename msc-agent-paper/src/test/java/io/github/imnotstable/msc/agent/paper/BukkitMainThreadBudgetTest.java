package io.github.imnotstable.msc.agent.paper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.agent.common.SlicedJob;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Concurrent jobs share one tick budget, as on the mod loaders (issue #247). */
class BukkitMainThreadBudgetTest {
    private ServerMock server;
    private BukkitMainThread main;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        main = new BukkitMainThread(MockBukkit.createMockPlugin(), Duration.ofMillis(20), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Finishes on its second step and records the deadline of each step. */
    private static final class TwoStepJob implements SlicedJob<Integer> {
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

    private void awaitQueued(int count) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (main.queued() < count) {
            assertThat(System.nanoTime()).as("jobs queued in time").isLessThan(end);
            Thread.sleep(1);
        }
    }

    @Test
    void concurrentJobsGetTheSameDeadlineEachTick() throws Exception {
        TwoStepJob first = new TwoStepJob();
        TwoStepJob second = new TwoStepJob();
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> main.run(first));
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> main.run(second));
        awaitQueued(2);

        for (int tick = 0; tick < 100 && !(a.isDone() && b.isDone()); tick++) {
            server.getScheduler().performOneTick();
            Thread.sleep(1);
        }

        assertThat(a.get(1, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(b.get(1, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(second.deadlines).hasSize(2).isEqualTo(first.deadlines);
        assertThat(main.queued()).isZero();
    }
}
