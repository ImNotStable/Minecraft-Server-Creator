package io.github.imnotstable.msc.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #499: {@link Budget.Parts#text} over random results (caps 0 to 24000, 0 to 80 parts, with and without a last line and
 * a hint, some parts ignoring their share): the result fits the cap; the fixed text and last line are there whenever
 * they fit; no part is left out or cut while the unused room would have shown it whole (exactly: the room it needs
 * beyond what it shows is more than the unused room, or more than the unused room minus the short summary line when
 * there is no summary line); the summary line counts exactly the parts not shown whole, and is there whenever a part
 * is left out and the short line fits; the same input gives the same text.
 */
class BudgetPartsPropertyTest {
    private static final Pattern NOTE = Pattern.compile("\n\\[(\\d+) of (\\d+) outputs cut(?: to fit\\. [^\n]*)?]$");

    /** One random result: its pieces in order (fixed text, or a part's whole text), and how each part renders. */
    private record Shape(List<String> fixed, List<String> wholes, List<Boolean> rude, int cap, String last, String hint) {
        static Shape random(long seed) {
            Random random = new Random(seed);
            int count = random.nextInt(81);
            List<String> fixed = new ArrayList<>();
            List<String> wholes = new ArrayList<>();
            List<Boolean> rude = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                // Every part but a leading one follows fixed text, so the test can tell where each part ends.
                fixed.add(i == 0 && random.nextBoolean() ? null
                        : (i == 0 ? "" : "\n") + "§" + i + "§ " + "f".repeat(random.nextInt(random.nextBoolean() ? 8 : 90)) + "\n");
                char c = (char) ('一' + i);
                int lines = random.nextInt(random.nextBoolean() ? 3 : 120);
                int width = 1 + random.nextInt(random.nextBoolean() ? 30 : 4);
                wholes.add((String.valueOf(c).repeat(width) + "\n").repeat(lines).stripTrailing());
                rude.add(random.nextInt(10) == 0);
            }
            int cap = random.nextInt(random.nextBoolean() ? 600 : 24_001);
            String last = random.nextBoolean() ? null : "next: " + "y".repeat(random.nextInt(30));
            String hint = random.nextBoolean() ? "" : Budget.RAISE;
            return new Shape(fixed, wholes, rude, cap, last, hint);
        }

        Budget.Parts parts() {
            Budget.Parts parts = new Budget.Parts();
            for (int i = 0; i < wholes.size(); i++) {
                String whole = wholes.get(i);
                boolean ignoresShare = rude.get(i);
                parts.add(fixed.get(i)).cut(n -> ignoresShare ? whole : Budget.truncate(whole, n, "Part cut."));
            }
            return parts;
        }

        int fixedChars() {
            int chars = fixed.stream().mapToInt(f -> f == null ? 0 : f.length()).sum();
            return chars + (last == null ? 0 : last.length() + (wholes.isEmpty() ? 0 : 1));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {499, 4990, 49_900})
    void everyResultFitsKeepsItsFixedTextWastesNoRoomAndCountsWhatItCut(long seed) {
        for (int round = 0; round < 1500; round++) {
            Shape shape = Shape.random(seed * 10_000 + round);
            String text = shape.parts().text(shape.cap(), shape.last(), shape.hint());
            String as = "seed " + seed + ", round " + round + ", cap " + shape.cap();
            assertThat(text.length()).as(as).isLessThanOrEqualTo(shape.cap());
            assertThat(shape.parts().text(shape.cap(), shape.last(), shape.hint())).as(as).isEqualTo(text);
            if (shape.fixedChars() > shape.cap()) {
                continue;
            }
            if (shape.last() != null) {
                assertThat(text).as(as).endsWith(shape.last());
            }
            String body = text.substring(0, text.length()
                    - (shape.last() == null ? 0 : shape.last().length() + (shape.wholes().isEmpty() ? 0 : 1)));
            Matcher note = NOTE.matcher(body);
            boolean noted = note.find();
            int end = noted ? note.start() : body.length();
            int n = shape.wholes().size();
            int shortNote = ("\n[" + n + " of " + n + " outputs cut]").length();
            int unused = shape.cap() - text.length();
            int notWhole = 0;
            boolean leftOut = false;
            int at = 0;
            for (int i = 0; i < n; i++) {
                String fixed = shape.fixed().get(i);
                if (fixed != null) {
                    assertThat(body.startsWith(fixed, at)).as(as + ", fixed " + i).isTrue();
                    at += fixed.length();
                }
                int to = i + 1 < n ? body.indexOf(shape.fixed().get(i + 1), at) : end;
                assertThat(to).as(as + ", part " + i).isGreaterThanOrEqualTo(at);
                String shown = body.substring(at, to);
                String whole = shape.wholes().get(i);
                if (!shown.equals(whole)) {
                    notWhole++;
                    leftOut |= shown.isEmpty() || shown.equals(Budget.LEFT_OUT);
                    int slack = noted ? 0 : shortNote;
                    assertThat(whole.length() - shown.length()).as(as + ", part " + i + " not whole: " + shown)
                            .isGreaterThan(unused - slack);
                }
                at = to;
            }
            assertThat(at).as(as).isEqualTo(end);
            if (noted) {
                assertThat(Integer.parseInt(note.group(1))).as(as).isEqualTo(notWhole);
                assertThat(Integer.parseInt(note.group(2))).as(as).isEqualTo(n);
            } else if (leftOut) {
                assertThat(shortNote).as(as + ": a part is left out and nothing counts it")
                        .isGreaterThan(shape.cap() - shape.fixedChars());
            }
        }
    }
}
