package io.github.imnotstable.msc.agent.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.agent.common.SlicedJob;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** ADR 0045: on Folia, server work runs on the global region thread and world jobs are refused. */
class GlobalRegionMainThreadTest {
    private final ExecutorService global = Executors.newSingleThreadExecutor(r -> new Thread(r, "global-region"));

    @AfterEach
    void stop() {
        global.shutdownNow();
    }

    private GlobalRegionMainThread main() {
        return new GlobalRegionMainThread(global, () -> Thread.currentThread().getName().equals("global-region"),
                Duration.ofSeconds(5));
    }

    @Test
    void callsRunOnTheGlobalRegionThreadAndPassFailuresOn() {
        AtomicReference<String> ranOn = new AtomicReference<>();
        assertThat(main().call(() -> {
            ranOn.set(Thread.currentThread().getName());
            return 42;
        })).isEqualTo(42);
        assertThat(ranOn).hasValue("global-region");
        assertThatThrownBy(() -> main().call(() -> {
            throw new IllegalArgumentException("bad");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("bad");
    }

    @Test
    void aCallOnTheGlobalThreadRunsInline() throws Exception {
        GlobalRegionMainThread main = main();
        assertThat(global.submit(() -> main.call(() -> Thread.currentThread().getName())).get())
                .isEqualTo("global-region");
    }

    @Test
    void aSchedulerThatRefusesMeansShutdown() {
        GlobalRegionMainThread main = new GlobalRegionMainThread(task -> {
            throw new IllegalStateException("plugin disabled");
        }, () -> false, Duration.ofSeconds(1));
        assertThatThrownBy(() -> main.call(() -> 1)).isInstanceOf(OpException.class)
                .extracting(e -> ((OpException) e).code()).isEqualTo(ErrorCodes.SHUTDOWN);
    }

    @Test
    void worldJobsAreRefused() {
        SlicedJob<Integer> job = new SlicedJob<>() {
            @Override
            public boolean step(long deadlineNanos) {
                return true;
            }

            @Override
            public Integer result() {
                return 1;
            }
        };
        assertThatThrownBy(() -> main().run(job)).isInstanceOf(OpException.class)
                .hasMessageContaining("not supported on Folia");
    }

    @Test
    void paperIsNotFolia() {
        assertThat(PaperServer.isFolia(getClass().getClassLoader())).isFalse();
    }
}
