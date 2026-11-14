package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommandOutputTest {
    private static List<String> capture(int max, String... lines) {
        CommandOutput out = new CommandOutput(max);
        for (String line : lines) {
            out.accept(line);
        }
        return out.lines();
    }

    @Test
    void keepsWhatFitsCutsTheLineThatCrossesAndCountsTheRest() {
        // 1000 chars, 128 kept free for the cut line: 872 for output, each line counting one more.
        String a = "a".repeat(400);
        assertThat(capture(1000, a, "b", null)).containsExactly(a, "b", "");
        assertThat(capture(1000, a, a, "c".repeat(400), "d", "e")).containsExactly(a, a,
                Limits.cut("c".repeat(400), 69), Ops.CommandResult.cutLine(2));
        // Too little room left to show a useful piece: the crossing line is counted too.
        assertThat(capture(1000, a, a, "x".repeat(20), "c".repeat(400), "d")).containsExactly(a, a, "x".repeat(20),
                Ops.CommandResult.cutLine(2));
        assertThat(capture(1000, "c".repeat(5000))).containsExactly(Limits.cut("c".repeat(5000), 871));
    }
}
