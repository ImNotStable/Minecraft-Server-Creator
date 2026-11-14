package io.github.imnotstable.msc.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** #473: a result of several parts spends its cap as one budget, so its closing line and the parts' hints survive. */
class BudgetPartsTest {
    private static final int[] CAPS = {200, 333, Budget.DEFAULT_CHARS, 9001, Budget.MAX_CHARS};

    @Test
    void splitGivesSmallWantsInFullAndNeverMoreThanTheTotal() {
        assertThat(Budget.split(100, 10, 500, 500)).containsExactly(10, 45, 45);
        assertThat(Budget.split(100, 10, 20)).containsExactly(10, 20);
        assertThat(Budget.split(-5, 10)).containsExactly(0);
        Random random = new Random(473);
        for (int i = 0; i < 2000; i++) {
            int[] wants = random.ints(1 + random.nextInt(5), 0, 30_000).toArray();
            int total = random.nextInt(30_000);
            int[] shares = Budget.split(total, wants);
            assertThat(Arrays.stream(shares).sum()).isLessThanOrEqualTo(total);
            for (int k = 0; k < wants.length; k++) {
                assertThat(shares[k]).isBetween(0, wants[k]);
            }
            if (Arrays.stream(wants).sum() <= total) {
                assertThat(shares).isEqualTo(wants);
            }
        }
    }

    private static String lines(String word, int count) {
        return (word + " line of output\n").repeat(count).stripTrailing();
    }

    /** dev_cycle's shape: a header, failed tests, deploy lines, a noisy start, and the time line last. */
    @Test
    void theLastLineAndEveryPartsHintSurviveAtEveryCap() {
        String tests = lines("test", 2000);
        String start = lines("start", 3000);
        for (int cap : CAPS) {
            String text = new Budget.Parts().add("TESTS FAILED in 1.0s\n")
                    .cut(n -> Budget.truncate(tests, n, "Fix the first errors."))
                    .add("\nbuild ok, deployed\nserver ready in 0.2s\n")
                    .cut(n -> Budget.truncate(start, n, "Use server_logs with grep to narrow."))
                    .text(cap, "time: jar 1.0s, total 2.0s", Budget.RAISE);
            assertThat(text).as("cap %d", cap).hasSizeLessThanOrEqualTo(cap).endsWith("\ntime: jar 1.0s, total 2.0s");
            if (cap >= Budget.DEFAULT_CHARS) {
                assertThat(text).as("cap %d", cap).contains("Fix the first errors.]")
                        .contains("Use server_logs with grep to narrow.]").contains("TESTS FAILED")
                        .contains("server ready").hasSizeGreaterThan(cap - 200);
            }
        }
    }

    @Test
    void fixedTextLongerThanTheCapIsCutBeforeTheLastLine() {
        for (int cap : CAPS) {
            String text = new Budget.Parts().add(lines("fixed", 5000)).cut(n -> Budget.truncate("part", n, "x"))
                    .text(cap, "last", Budget.RAISE);
            assertThat(text).hasSizeLessThanOrEqualTo(cap).endsWith(Budget.RAISE + "]\nlast");
            assertThat(new Budget.Parts().add(lines("fixed", 5000)).text(cap, null, "Narrow it."))
                    .hasSizeLessThanOrEqualTo(cap).endsWith("Narrow it.]");
        }
        assertThat(new Budget.Parts().add("a\n").cut(n -> "b").text(200, "c", "x")).isEqualTo("a\nb\nc");
        assertThat(new Budget.Parts().text(200, "only", "x")).isEqualTo("only");
    }
}
