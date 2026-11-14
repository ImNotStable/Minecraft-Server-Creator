package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.imnotstable.msc.bench.check.CheckResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                assertThat(Text.cut(text, max).length()).isLessThanOrEqualTo(max);
                for (String cut : new String[] {head, tail, Text.headWithEllipsis(text, max + 3),
                        Text.tailWithEllipsis(text, max + 3), Text.cut(text, max), Text.headWithEllipsis(text, max),
                        Text.tailWithEllipsis(text, max)}) {
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
        // Of 300 chars the cut keeps a 73-char head, " ... " and a 222-char tail; both cuts fall inside an emoji.
        String text = "x".repeat(72) + EMOJI + "y".repeat(103) + EMOJI + "z".repeat(221);
        CheckResult result = CheckResult.fail("n", text);
        assertThat(Text.hasLoneSurrogate(result.detail())).isFalse();
        assertThat(result.detail()).startsWith("xxx").contains("x" + Text.CUT_MARK + "z").endsWith("zzz")
                .hasSizeLessThanOrEqualTo(CheckResult.MAX_DETAIL);
    }

    @Test
    void cutKeepsHeadAndTailAndLeavesShortTextAlone() {
        assertThat(Text.cut("short", 300)).isEqualTo("short");
        assertThat(Text.cut("a".repeat(100) + "b".repeat(100), 45)).isEqualTo("a".repeat(10) + Text.CUT_MARK
                + "b".repeat(30));
        assertThat(Text.cut("abcdefgh", 4)).isEqualTo("efgh");
    }

    @Test
    void aFileOfTheBenchsOwnThatIsNotUtf8IsRefusedNamingItAndTheByte(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("task.json");
        Files.write(file, "{\"title\":\"caf\u00e9\"}".getBytes(StandardCharsets.ISO_8859_1));
        Path good = dir.resolve("good.json");
        Files.writeString(good, "{\"title\":\"caf\u00e9 \uD83D\uDE00\"}");

        assertThat(catchThrowable(() -> Text.readOwn(file)))
                .isInstanceOf(IOException.class)
                .hasMessage(file + ": the byte at offset 13 is not UTF-8 text; save the file as UTF-8");
        assertThat(Text.readOwn(good)).isEqualTo("{\"title\":\"caf\u00e9 \uD83D\uDE00\"}");
    }
}
