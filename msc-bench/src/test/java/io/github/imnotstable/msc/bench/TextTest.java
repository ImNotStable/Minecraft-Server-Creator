package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.check.CheckResult;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** #462: no cut of captured text leaves half a surrogate pair, and every text can be written as UTF-8. */
class TextTest {

    private static final String EMOJI = "\uD83D\uDE00";

    @Test
    void everyCutOfATextWithEmojiAtEveryPlaceKeepsWholeCharacters() {
        for (int at = 0; at <= 12; at++) {
            String text = "x".repeat(at) + EMOJI + "y".repeat(12 - at) + EMOJI;
            for (int max = 0; max <= text.length() + 1; max++) {
                String head = Text.head(text, max);
                String tail = Text.tail(text, max);
                assertThat(head.length()).isLessThanOrEqualTo(max).isGreaterThanOrEqualTo(Math.min(max, text.length()) - 1);
                assertThat(tail.length()).isLessThanOrEqualTo(max).isGreaterThanOrEqualTo(Math.min(max, text.length()) - 1);
                assertThat(text).startsWith(head).endsWith(tail);
                for (String cut : new String[] {head, tail, Text.headWithEllipsis(text, max + 3),
                        Text.tailWithEllipsis(text, max + 3)}) {
                    assertThat(Text.hasLoneSurrogate(cut)).as("%d %d %s", at, max, cut).isFalse();
                }
            }
        }
    }

    @Test
    void writableReplacesOnlyLoneSurrogates() {
        String clean = "a" + EMOJI + "b";
        assertThat(Text.writable(clean)).isSameAs(clean);
        assertThat(Text.writable("\uD83Da\uDE00" + EMOJI + "\uD83D")).isEqualTo("\uFFFDa\uFFFD" + EMOJI + "\uFFFD");
        assertThat(Text.writable("\uDE00\uD83D")).isEqualTo("\uFFFD\uFFFD");
        String writable = Text.writable("x\uD800");
        assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(writable)).isTrue();
    }

    @Test
    void aCheckDetailCutAtAnEmojiKeepsItWhole() {
        // The detail keeps 297 chars and "...": the cut falls between the halves of the emoji at 296.
        CheckResult result = CheckResult.fail("n", "x".repeat(296) + EMOJI + " tail");
        assertThat(Text.hasLoneSurrogate(result.detail())).isFalse();
        assertThat(result.detail()).endsWith("x...").hasSizeLessThanOrEqualTo(300);
    }
}
