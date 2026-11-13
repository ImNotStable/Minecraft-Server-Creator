package io.github.imnotstable.msc.agent.paper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.agent.common.SlicedJob;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;

class BukkitMainThreadTest {
    /** A scheduler whose timers tick before {@code runTaskTimer} returns, as the server thread may. */
    private static final class EagerScheduler extends BukkitSchedulerMock {
        @Override
        public BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
            BukkitTask handle = super.runTaskTimer(plugin, task, delay, period);
            while (!handle.isCancelled()) {
                task.run();
            }
            return handle;
        }

        @Override
        public void runTaskTimer(Plugin plugin, Consumer<? super BukkitTask> task, long delay, long period) {
            BukkitTask handle = super.runTaskTimer(plugin, () -> { }, delay, period);
            while (!handle.isCancelled()) {
                task.accept(handle);
            }
        }
    }

    // ServerMock's own raw getBanList override is reported on every subclass.
    @SuppressWarnings("unchecked")
    private static final class EagerServer extends ServerMock {
        private final EagerScheduler scheduler = new EagerScheduler();

        @Override
        public BukkitSchedulerMock getScheduler() {
            return scheduler;
        }
    }

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock(new EagerServer());
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void jobFinishingInItsFirstTickBeforeTheTimerReturns() {
        BukkitMainThread main = new BukkitMainThread(plugin, Duration.ofMillis(20), Duration.ofSeconds(5));
        SlicedJob<String> job = new SlicedJob<>() {
            @Override
            public boolean step(long deadlineNanos) {
                return true;
            }

            @Override
            public String result() {
                return "done";
            }
        };
        // off the primary thread, like a request from the core
        assertThat(CompletableFuture.supplyAsync(() -> main.run(job)).join()).isEqualTo("done");
    }

    @Test
    void aJobAfterTheTaskStoppedStartsItAgain() {
        BukkitMainThread main = new BukkitMainThread(plugin, Duration.ofMillis(20), Duration.ofSeconds(5));
        for (int round = 1; round <= 2; round++) {
            int[] steps = {0};
            SlicedJob<Integer> job = new SlicedJob<>() {
                @Override
                public boolean step(long deadlineNanos) {
                    return ++steps[0] == 2;
                }

                @Override
                public Integer result() {
                    return steps[0];
                }
            };

            assertThat(CompletableFuture.supplyAsync(() -> main.run(job)).join()).as("round %d", round).isEqualTo(2);
            assertThat(main.queued()).isZero();
        }
    }
}
