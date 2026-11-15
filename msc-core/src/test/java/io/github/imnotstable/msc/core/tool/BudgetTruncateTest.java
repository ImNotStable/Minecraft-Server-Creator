package io.github.imnotstable.msc.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.Limits;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** #487: {@link Budget#truncate} keeps its bound for every maxChars, the small ones included. */
class BudgetTruncateTest {
    private static final List<String> HINTS = List.of("", "x", "Output cut.", Budget.RAISE, "h".repeat(120));
    /** Letters, line breaks, an emoji (a surrogate pair) and an accented letter; no brackets, so markers are clear. */
    private static final String[] ALPHABET = {"a", "b", "\n", "😀", "é"};

    private static List<String> texts() {
        Random random = new Random(487);
        List<String> texts = new ArrayList<>(List.of("", "a", "\n", "😀", "Successfully filled 726 block(s)"));
        for (int i = 0; i < 150; i++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(i < 100 ? 120 : 400);
            while (text.length() < length) {
                text.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            }
            texts.add(text.toString());
        }
        return texts;
    }

    @Test
    void neverLongerThanMaxCharsAndHonestAboutWhatWasCut() {
        for (String hint : HINTS) {
            Pattern full = Pattern.compile("(?s)(.*?)\\n?\\[truncated: (\\d+) more lines, (\\d+) chars\\. "
                    + Pattern.quote(hint) + "]");
            Pattern brief = Pattern.compile("(?s)(.*?)\\n?\\[(\\d+) chars cut]");
            for (String text : texts()) {
                for (int maxChars = -2; maxChars <= 260; maxChars++) {
                    String cut = Budget.truncate(text, maxChars, hint);
                    String as = "maxChars " + maxChars + ", hint " + hint.length() + ", text " + text.length();
                    assertThat(cut.length()).as(as).isLessThanOrEqualTo(Math.max(0, maxChars));
                    assertWholePairs(cut, as);
                    if (text.length() <= Math.max(0, maxChars)) {
                        assertThat(cut).as(as).isEqualTo(text);
                        continue;
                    }
                    Matcher f = full.matcher(cut);
                    Matcher b = brief.matcher(cut);
                    if (f.matches()) {
                        assertThat(text).as(as).startsWith(f.group(1));
                        assertThat(Integer.parseInt(f.group(3))).as(as).isEqualTo(text.length() - f.group(1).length());
                    } else if (b.matches()) {
                        assertThat(maxChars).as(as).isLessThan(55 + hint.length() + 20);
                        assertThat(text).as(as).startsWith(b.group(1));
                        assertThat(Integer.parseInt(b.group(2))).as(as).isEqualTo(text.length() - b.group(1).length());
                    } else {
                        // Too small even for the short line: the plain start of the text.
                        assertThat(maxChars).as(as).isLessThan(16);
                        assertThat(cut).as(as).isEqualTo(Limits.head(text, Math.max(0, maxChars)));
                    }
                    if (maxChars >= 75 + hint.length()) {
                        assertThat(f.matches()).as(as).isTrue();
                    }
                }
            }
        }
    }

    /** #614: the narrowing advice comes first; raising maxChars is offered only while a larger one is allowed. */
    @Test
    void raiseIsOfferedOnlyBelowTheLargestCap() {
        assertThat(Budget.raise(Budget.MAX_CHARS - 1, "Ask for less."))
                .isEqualTo("Ask for less. Or raise maxChars (at most 24000).");
        assertThat(Budget.raise(Budget.MAX_CHARS, "Ask for less.")).isEqualTo("Ask for less.");
    }

    @Test
    void theIssuesOutputIsNeverReplacedByALongerMarker() {
        String output = "Successfully filled 726 block(s)";
        for (int share = 0; share < output.length(); share++) {
            assertThat(Budget.truncate(output, share, "Output cut.")).hasSizeLessThanOrEqualTo(share);
        }
        assertThat(Budget.truncate(output, 17, "Output cut.")).isEqualTo("Su\n[30 chars cut]");
        assertThat(Budget.truncate(output, 5, "Output cut.")).isEqualTo("Succe");
        assertThat(Budget.truncate("one\ntwo\nthree\nfour\nfive", 20, "Output cut.")).isEqualTo("one\n[20 chars cut]");
        assertThat(Budget.truncate("one\ntwo\nthree\nfour\nfive", 12, "Output cut.")).isEqualTo("one\ntwo\nthre");
        assertThat(Budget.truncate(output, -1, "Output cut.")).isEmpty();
    }

    private static void assertWholePairs(String text, String as) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertThat(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))).as(as).isTrue();
            } else if (Character.isLowSurrogate(c)) {
                assertThat(i > 0 && Character.isHighSurrogate(text.charAt(i - 1))).as(as).isTrue();
            }
        }
    }
}
