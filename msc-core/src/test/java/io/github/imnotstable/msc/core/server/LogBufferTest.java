package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class LogBufferTest {
    @Test
    void awaitFindsALineAddedLater() throws Exception {
        LogBuffer log = new LogBuffer(10, Long.MAX_VALUE);
        log.add("old done");
        long mark = log.mark();
        Thread.ofVirtual().start(() -> {
            log.add("noise");
            log.add("done");
        });
        LogBuffer.Match m = log.await(mark, l -> l.equals("done"), Duration.ofSeconds(10), () -> true);
        assertThat(m).isEqualTo(new LogBuffer.Match("done", 2));
    }

    @Test
    void awaitStopsWhenNotAlive() throws Exception {
        LogBuffer log = new LogBuffer(10, Long.MAX_VALUE);
        assertThat(log.await(0, l -> true, Duration.ofSeconds(10), () -> false)).isNull();
    }

    @Test
    void awaitChecksAliveWithoutHoldingTheBuffer() throws Exception {
        // server_wait passes server::running, which takes the ServerManager monitor, while ServerManager.start
        // holds that monitor and calls log.mark(): alive must not run under the buffer's monitor (#242).
        LogBuffer log = new LogBuffer(10, Long.MAX_VALUE);
        AtomicBoolean markedWhileChecking = new AtomicBoolean();
        LogBuffer.Match m = log.await(0, l -> false, Duration.ofMillis(300), () -> {
            try {
                CompletableFuture.supplyAsync(log::mark).get(2, TimeUnit.SECONDS);
                markedWhileChecking.set(true);
            } catch (Exception e) {
                markedWhileChecking.set(false);
            }
            return false;
        });
        assertThat(m).isNull();
        assertThat(markedWhileChecking).isTrue();
    }
}
