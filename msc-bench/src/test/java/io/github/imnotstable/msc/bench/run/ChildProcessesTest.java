package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChildProcessesTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElse("java");

    @Test
    void killAllKillsLiveProcessesAndRefusesLaterStarts() throws IOException, InterruptedException {
        ChildProcesses children = new ChildProcesses();
        // Waits on standard input, which stays open: alive until killed.
        Process process = children.start(new ProcessBuilder(JAVA, "-cp", System.getProperty("java.class.path"),
                "io.github.imnotstable.msc.bench.FakeServer"));

        children.killAll();

        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(children.closed()).isTrue();
        assertThat(catchThrowable(() -> children.start(new ProcessBuilder(JAVA, "-version"))))
                .isInstanceOf(IOException.class).hasMessageContaining("shutting down");
    }
}
