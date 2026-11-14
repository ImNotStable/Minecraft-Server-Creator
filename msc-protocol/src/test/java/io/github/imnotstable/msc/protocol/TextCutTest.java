package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.CharBuffer;
import org.junit.jupiter.api.Test;

/** Every cut of outside text keeps surrogate pairs whole, wherever the boundary falls (#465). */
class TextCutTest {
    private static final String EMOJI = "😀";

    @Test
    void headAndShortenNeverLeaveHalfASurrogatePairAtAnyBoundary() throws CharacterCodingException {
        for (int prefix = 0; prefix < 6; prefix++) {
            String text = "a".repeat(prefix) + EMOJI.repeat(40);
            for (int max = 0; max <= text.length() + 1; max++) {
                String head = Limits.head(text, max);
                assertThat(head.length()).isLessThanOrEqualTo(max).isGreaterThanOrEqualTo(Math.min(max, text.length()) - 1);
                assertThat(text).startsWith(head);
                encodes(head);
                if (max >= 4) {
                    String preview = Limits.shorten(text, max);
                    assertThat(preview.length()).isLessThanOrEqualTo(max);
                    encodes(preview);
                }
                if (max >= 64) {
                    encodes(Limits.cut(text, max));
                }
            }
        }
        assertThat(Limits.shorten("short", 60)).isEqualTo("short");
        assertThat(Limits.shorten("abcdefgh", 6)).isEqualTo("abc...");
        assertThat(Limits.head(null, 3)).isNull();
        assertThatThrownBy(() -> Limits.shorten("x", 3)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void encodes(String s) throws CharacterCodingException {
        StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(s)); // throws on half a pair
    }
}
