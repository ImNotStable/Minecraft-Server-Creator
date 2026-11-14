package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** #533: a reader that hears "the server stopped" sees every line the server printed before it exited. */
@DisabledOnOs(OS.WINDOWS)
class LogPumpTest {
    private static final String MARK = "CRASHMARK";
    private static final Duration GENEROUS = Duration.ofMinutes(2);

    private static Process fake(String script) throws Exception {
        return new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start();
    }

    @Test
    void aLinePrintedJustBeforeTheExitIsAlwaysFound() throws Exception {
        // A burst, then the awaited line, then the exit: the process is dead before the pump has read it all.
        String script = "i=0; while [ $i -lt 60 ]; do echo \"burst line $i\"; i=$((i+1)); done; echo " + MARK
                + "; exit 1";
        int matched = 0;
        for (int run = 0; run < 200; run++) {
            LogBuffer log = new LogBuffer(1_000, Long.MAX_VALUE);
            Process p = fake(script);
            LogPump pump = LogPump.start(p, log, () -> { }, GENEROUS);
            LogBuffer.Match m = pump.await(log, 0, l -> l.equals(MARK), GENEROUS);
            if (m != null && m.seq() == 60) {
                matched++;
            }
        }
        assertThat(matched).isEqualTo(200);
    }

    @Test
    void aPipeAChildHoldsOpenStillEndsTheWait() throws Exception {
        // The shell exits, its background child keeps stdout open: the pump never reaches the end of the output.
        // When the pump is not inside a read at the exit, the JDK drains and closes the pipe itself, so a run may
        // end the ordinary way; retry until one held the pipe. Every run must end.
        boolean held = false;
        for (int run = 0; run < 5 && !held; run++) {
            held = childHoldsThePipe();
        }
        assertThat(held).isTrue();
    }

    private static boolean childHoldsThePipe() throws Exception {
        LogBuffer log = new LogBuffer(100, Long.MAX_VALUE);
        // The pause lets the pump read the line and block in the next read before the shell exits.
        Process p = fake("sleep 600 & echo \"child $!\"; sleep 1; exit 0");
        LogPump pump = LogPump.start(p, log, () -> { }, Duration.ofSeconds(1));
        long child = -1;
        try {
            LogBuffer.Match first = log.await(0, l -> l.startsWith("child "), GENEROUS, () -> true);
            child = Long.parseLong(first.line().substring("child ".length()));
            assertThat(pump.await(log, 0, "never printed"::equals, GENEROUS)).isNull();
            assertThat(log.all()).containsExactly(first.line());
            // Bounded once per process: a later reader finds the log as complete as it gets.
            assertThat(pump.awaitEnd()).isTrue();
            return !pump.finished();
        } finally {
            if (child > 0) {
                ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void aRunningProcessIsNotEnded() throws Exception {
        LogBuffer log = new LogBuffer(100, Long.MAX_VALUE);
        Process p = fake("echo up; exec sleep 600");
        try {
            LogPump pump = LogPump.start(p, log, () -> { }, GENEROUS);
            assertThat(pump.await(log, 0, "up"::equals, GENEROUS)).isNotNull();
            assertThat(pump.awaitEnd()).isFalse();
        } finally {
            p.destroyForcibly().waitFor();
        }
    }
}
