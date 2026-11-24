package io.github.imnotstable.msc.agent.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.agent.common.ChunkArea;
import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.SlicedJob;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ADR 0100: world jobs on Folia run on the region thread that owns their chunks, a slice per tick. */
class RegionThreadsTest {
    private static final ChunkArea AREA = new ChunkArea("world", -2, -2, 3, 1);
    private final ScheduledExecutorService region = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "region"));
    private final List<int[]> scheduledAt = new ArrayList<>();
    /** How many ownership checks on the region thread say no before the region owns the area (a merge in progress). */
    private final AtomicInteger notYet = new AtomicInteger();

    @AfterEach
    void stop() {
        region.shutdownNow();
    }

    private RegionThreads threads(Duration timeout) {
        RegionThreads.Regions regions = new RegionThreads.Regions() {
            @Override
            public void schedule(String world, int cx, int cz, long delayTicks, Runnable task) {
                synchronized (scheduledAt) {
                    scheduledAt.add(new int[] {cx, cz});
                }
                region.schedule(task, delayTicks, TimeUnit.MILLISECONDS);
            }

            @Override
            public boolean owns(ChunkArea area) {
                return Thread.currentThread().getName().equals("region") && notYet.getAndDecrement() <= 0;
            }
        };
        return new RegionThreads(MainThread.inline(), regions, Duration.ofMillis(5), timeout);
    }

    /** A job of {@code slices} steps that records the thread of each. */
    private static final class Slices implements SlicedJob<List<String>> {
        final List<String> threads = new ArrayList<>();
        final int slices;

        Slices(int slices) {
            this.slices = slices;
        }

        @Override
        public boolean step(long deadlineNanos) {
            threads.add(Thread.currentThread().getName());
            return threads.size() == slices;
        }

        @Override
        public List<String> result() {
            return threads;
        }
    }

    @Test
    void aJobRunsOnItsRegionInSlicesStartingAtTheAreasMiddleChunk() {
        assertThat(threads(Duration.ofSeconds(5)).run(new Slices(4), AREA)).containsExactly("region", "region", "region",
                "region");
        assertThat(scheduledAt).hasSize(4).allSatisfy(c -> assertThat(c).containsExactly(0, -1));
    }

    @Test
    void aJobWaitsForItsChunksToMergeIntoOneRegion() {
        notYet.set(3);
        assertThat(threads(Duration.ofSeconds(5)).run(new Slices(2), AREA)).hasSize(2);
        assertThat(scheduledAt).hasSize(5);
    }

    @Test
    void anAreaThatNeverComesUnderOneRegionFailsInsteadOfTouchingForeignChunks() {
        notYet.set(Integer.MAX_VALUE);
        Slices job = new Slices(1);
        assertThatThrownBy(() -> threads(Duration.ofSeconds(10)).run(job, AREA)).isInstanceOf(OpException.class)
                .hasMessageContaining("did not come under one Folia region")
                .extracting(e -> ((OpException) e).code()).isEqualTo(ErrorCodes.TOO_LARGE);
        assertThat(job.threads).isEmpty();
    }

    @Test
    void callsWithAnAreaRunOnTheRegionAndOthersOnTheGlobalThread() {
        RegionThreads t = threads(Duration.ofSeconds(5));
        assertThat(t.call(() -> Thread.currentThread().getName(), AREA)).isEqualTo("region");
        assertThat(t.call(() -> Thread.currentThread().getName())).isEqualTo(Thread.currentThread().getName());
        assertThatThrownBy(() -> t.run(new Slices(1))).isInstanceOf(OpException.class).hasMessageContaining("chunks");
    }

    @Test
    void aFailingSliceFailsTheCallAndATimeoutStopsTheJob() throws Exception {
        RegionThreads t = threads(Duration.ofSeconds(5));
        assertThatThrownBy(() -> t.call(() -> {
            throw new IllegalArgumentException("bad block");
        }, AREA)).isInstanceOf(IllegalArgumentException.class).hasMessage("bad block");
        AtomicInteger steps = new AtomicInteger();
        SlicedJob<Integer> endless = new SlicedJob<>() {
            @Override
            public boolean step(long deadlineNanos) {
                steps.incrementAndGet();
                return false;
            }

            @Override
            public Integer result() {
                return null;
            }
        };
        assertThatThrownBy(() -> threads(Duration.ofMillis(200)).run(endless, AREA)).isInstanceOf(OpException.class)
                .extracting(e -> ((OpException) e).code()).isEqualTo(ErrorCodes.TIMEOUT);
        int after = steps.get();
        Thread.sleep(100);
        assertThat(steps.get()).isLessThanOrEqualTo(after + 1);
    }
}
