package io.github.imnotstable.msc.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** #611: {@link Budget#tail} keeps the end of a log tail, within its bound for every maxChars. */
class BudgetTailTest {
    private static final List<String> HINTS = List.of("", "x", "Output cut.", Budget.RAISE, "h".repeat(120));
    private static final String[] ALPHABET = {"a", "b", "\n", "😀", "é"};

    private static List<String> texts() {
        Random random = new Random(611);
        List<String> texts = new ArrayList<>(List.of("", "a", "\n", "😀", "one\ntwo\n"));
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
    void neverLongerThanMaxCharsAndKeepsTheEnd() {
        for (String hint : HINTS) {
            Pattern full = Pattern.compile("(?s)\\[truncated: (\\d+) earlier lines, (\\d+) chars\\. "
                    + Pattern.quote(hint) + "]\\n?(.*)");
            Pattern brief = Pattern.compile("(?s)\\[(\\d+) chars cut]\\n?(.*)");
            for (String text : texts()) {
                for (int maxChars = -2; maxChars <= 260; maxChars++) {
                    String cut = Budget.tail(text, maxChars, hint);
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
                        String kept = f.group(3);
                        assertThat(text).as(as).endsWith(kept);
                        int from = text.length() - kept.length();
                        assertThat(Integer.parseInt(f.group(2))).as(as).isEqualTo(from);
                        long breaks = text.substring(0, from).chars().filter(c -> c == '\n').count();
                        long lines = text.charAt(from - 1) == '\n' ? breaks : breaks + 1;
                        assertThat(Long.parseLong(f.group(1))).as(as).isEqualTo(Math.max(1, lines));
                    } else if (b.matches()) {
                        assertThat(text).as(as).endsWith(b.group(2));
                        assertThat(Integer.parseInt(b.group(1))).as(as).isEqualTo(text.length() - b.group(2).length());
                    } else {
                        assertThat(maxChars).as(as).isLessThan(16);
                        assertThat(text).as(as).endsWith(cut);
                    }
                    if (maxChars >= 70 + hint.length()) {
                        assertThat(f.matches()).as(as).isTrue();
                    }
                }
            }
        }
    }

    @Test
    void theNewestLineSurvivesAndTheCutStartsAtALine() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            log.append(i == 0 ? "" : "\n").append("wave ").append(i).append(' ').append("w".repeat(250));
        }
        String cut = Budget.tail(log.toString(), Budget.DEFAULT_CHARS, Budget.RAISE);
        assertThat(cut).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS)
                .startsWith("[truncated: 18 earlier lines, ").endsWith("wave 39 " + "w".repeat(250))
                .contains("\nwave 18 w");
        assertThat(Budget.tail("one\ntwo\nthree\nfour\nfive", 20, "")).isEqualTo("[19 chars cut]\nfive");
        assertThat(Budget.tail("one\ntwo\nthree", 8, "")).isEqualTo("wo\nthree");
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
