package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** #620: dev_cycle's own cut hint names only what shows what the result held, never server_logs for its own lines. */
class DevCycleHintTest {
    @Test
    void theHintNamesWhatThisResultHeld() {
        assertThat(DevCycle.hint(300, "demo/build/test-results", true)).isEqualTo("Failed tests: "
                + "demo/build/test-results. Startup log: server_logs. Or raise maxChars (at most 24000).");
        assertThat(DevCycle.hint(24_000, "demo/build/test-results", false))
                .isEqualTo("Failed tests: demo/build/test-results.");
        assertThat(DevCycle.hint(24_000, null, true)).isEqualTo("Startup log: server_logs.");
        // A failed compile, or a run that wrote no test results and started nothing: only a larger cap shows more.
        assertThat(DevCycle.hint(200, null, false)).isEqualTo("Raise maxChars (at most 24000).");
        assertThat(DevCycle.hint(24_000, null, false)).isEqualTo("No other tool shows these lines.");
    }
}
