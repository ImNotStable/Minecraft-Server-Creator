package io.github.imnotstable.msc.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    /**
     * #487, #499: server_command's shape, 64 commands of 72 characters each answered with 32. Every command line is
     * kept, the outputs are shown whole smallest first as many as fit beside the short summary line (34: 34 x 32 plus
     * the 23-character line fit the 1,137 left; 35 do not), and the rest are counted.
     */
    @Test
    void manySmallOutputsAreShownWholeOrCountedAndEveryCommandLineIsKept() {
        String output = "Successfully filled 726 block(s)";
        Budget.Parts parts = new Budget.Parts();
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            String command = "fill 0 64 " + i + " 10 70 10 minecraft:stone_bricks replace minecraft:air" + " ".repeat(8);
            commands.add("> " + command.substring(0, 72));
            parts.add((i == 0 ? "" : "\n") + "> " + command.substring(0, 72) + "\n")
                    .cut(n -> Budget.truncate(output, n, "Output cut."));
        }
        String text = parts.text(Budget.DEFAULT_CHARS, null, "Run fewer commands per call.");
        assertThat(text).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS).doesNotContain("Output cut.]");
        assertThat(text.lines().filter(l -> l.startsWith("> "))).containsExactlyElementsOf(commands);
        assertThat(text.lines().filter(output::equals)).hasSize(34);
        assertThat(text).endsWith("\n[30 of 64 outputs cut]");
    }

    /**
     * #499: the issue's shape, 64 commands of 84 characters answered with 29 in 369 characters. Without the plan that
     * dropped outputs to pay for markers it then could not show, 11 outputs fit whole (11 x 29 + 23 = 342),
     * not 2. The summary line is short: the hint would cost more than the twelfth output.
     */
    @Test
    void outputsDroppedForMarkersThatDoNotFitAreShown() {
        Budget.Parts parts = new Budget.Parts();
        for (int i = 0; i < 64; i++) {
            String command = ("setblock %2d 64 0 minecraft:oak_sign[rotation=4]{front_text:{messages:['a','b']}} " + "x".repeat(20))
                    .formatted(i).substring(0, 84);
            String output = "Changed the block at %2d,64,0.".formatted(i);
            parts.add((i == 0 ? "" : "\n") + "> " + command + "\n").cut(n -> Budget.truncate(output, n, "Output cut."));
        }
        String text = parts.text(Budget.DEFAULT_CHARS, null, "Run fewer commands per call.");
        assertThat(text.lines().filter(l -> l.startsWith("Changed the block at "))).hasSize(11);
        assertThat(text).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS).endsWith("\n[53 of 64 outputs cut]");
    }

    /** Two large parts in little room: one gets a useful share rather than both a share of little but cut lines. */
    @Test
    void aShareTooSmallToBeUsefulGoesToAnotherPart() {
        String big = lines("big", 100);
        String text = new Budget.Parts().add("head\n").cut(n -> Budget.truncate(big, n, "x")).add("\n")
                .cut(n -> Budget.truncate(big, n, "y")).text(160, "last", "Narrow it.");
        assertThat(text).hasSizeLessThanOrEqualTo(160).startsWith("head\n").contains("big line of output")
                .contains("\n[cut]\n[2 of 2 outputs cut to fit. Narrow it.]\nlast").endsWith("\nlast");
    }

    /**
     * #487: for random fixed text, parts (some rendering longer than their share) and caps from 0 up: the result never
     * exceeds the cap, and when the fixed text and closing line fit, every fixed piece is kept in order and the
     * closing line is last.
     */
    @Test
    void theResultFitsEveryCapAndKeepsTheFixedTextWhenItFits() {
        Random random = new Random(487);
        for (int round = 0; round < 3000; round++) {
            Budget.Parts parts = new Budget.Parts();
            List<String> fixed = new ArrayList<>();
            int count = 1 + random.nextInt(round % 3 == 0 ? 70 : 6);
            for (int i = 0; i < count; i++) {
                if (random.nextBoolean()) {
                    String piece = "\n> fixed " + i + " " + "f".repeat(random.nextInt(40)) + "\n";
                    fixed.add(piece);
                    parts.add(piece);
                }
                String part = lines("part" + i, random.nextInt(random.nextBoolean() ? 3 : 200));
                boolean rude = random.nextInt(10) == 0;
                parts.cut(n -> rude ? part : Budget.truncate(part, n, "Cut " + "h".repeat(random.nextInt(3) * 30)));
            }
            String last = random.nextInt(4) == 0 ? null : "last line " + round;
            int cap = random.nextInt(round % 2 == 0 ? 400 : 24_001);
            String text = parts.text(cap, last, "Narrow it.");
            String as = "round " + round + ", cap " + cap;
            assertThat(text.length()).as(as).isLessThanOrEqualTo(cap);
            int fixedChars = fixed.stream().mapToInt(String::length).sum() + (last == null ? 0 : last.length() + 1);
            if (fixedChars <= cap) {
                int at = 0;
                for (String piece : fixed) {
                    at = text.indexOf(piece, at);
                    assertThat(at).as(as).isNotNegative();
                    at += piece.length();
                }
                if (last != null) {
                    assertThat(text).as(as).endsWith(last);
                }
            }
        }
    }
}
