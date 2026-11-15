package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.check.CheckResult;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/** #661, #662: the one way a refusal shows what a user wrote. */
class QuotedTest {

    static Stream<Arguments> strings() {
        return Stream.of(
                Arguments.of("26.3\n", "\"26.3\\n\""),
                Arguments.of("../x ", "\"../x \""),
                Arguments.of(" lead", "\" lead\""),
                Arguments.of("a\rb\tc", "\"a\\rb\\tc\""),
                Arguments.of("a\"b\\c", "\"a\\\"b\\\\c\""),
                Arguments.of("a\0", "\"a\\u0000\""),
                Arguments.of("a\u00a0", "\"a\\u00a0\""),
                Arguments.of("a\u0085b", "\"a\\u0085b\""),
                Arguments.of("a\u2028b\u2029", "\"a\\u2028b\\u2029\""),
                Arguments.of("\u202eabc", "\"\\u202eabc\""),
                Arguments.of("a\u200b", "\"a\\u200b\""),
                Arguments.of("a\ud800", "\"a\\ud800\""),
                Arguments.of("é日本\uD83D\uDE00", "\"é日本\uD83D\uDE00\""));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("strings")
    void aStringIsQuotedOnOneLineWithEveryHiddenCharacterEscaped(String text, String shown) {
        assertThat(Quoted.text(text)).isEqualTo(shown);
    }

    static Stream<Arguments> messages() {
        return Stream.of(
                Arguments.of("Unclosed group", "Unclosed group"),
                Arguments.of("  padded \n", "padded"),
                Arguments.of("name {a b‮c}", "name {a\\u2028b\\u202ec}"),
                Arguments.of("two\nlines\rand\ttab", "two\\nlines\\rand\\ttab"),
                Arguments.of("quote \" and \\ stay", "quote \" and \\ stay"),
                Arguments.of("nul \0, ​,   and \u0085", "nul \\u0000, \\u200b, \\u00a0 and \\u0085"),
                Arguments.of("lone \ud800", "lone \\ud800"),
                Arguments.of("é日本😀", "é日本😀"));
    }

    /** #672: a library's or an exception's own text, appended to a refusal, is escaped as a value is, but unquoted. */
    @ParameterizedTest(name = "{1}")
    @MethodSource("messages")
    void aMessageIsEscapedOntoOneLineWithoutQuotes(String message, String shown) {
        assertThat(Quoted.message(message)).isEqualTo(shown);
    }

    @Test
    void aLongMessageIsCutAfterWholeCharactersAndNullIsEmpty() {
        assertThat(Quoted.message(null)).isEmpty();
        assertThat(Quoted.message("a".repeat(300))).isEqualTo("a".repeat(Quoted.MESSAGE_MAX) + "...");
        assertThat(Quoted.message("x ".repeat(300))).isEqualTo("x\\u2028".repeat(Quoted.MESSAGE_MAX / 2) + "...");
        assertThat(Quoted.message("a".repeat(Quoted.MESSAGE_MAX))).isEqualTo("a".repeat(Quoted.MESSAGE_MAX));
    }

    @Test
    void aLongValueIsCutAfterWholeCharactersAndClosed() {
        assertThat(Quoted.text("a".repeat(100))).isEqualTo("\"" + "a".repeat(60) + "\"...");
        // Each line break is one character: the cut never splits its escape.
        assertThat(Quoted.text("\n".repeat(100))).isEqualTo("\"" + "\\n".repeat(60) + "\"...");
        assertThat(Quoted.text("a".repeat(60))).isEqualTo("\"" + "a".repeat(60) + "\"");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            12345678          | a number of 8 characters
            '"hunter2"'       | a string of 7 characters
            '["hunter2"]'     | a list of 1 entry
            '["a","b"]'       | a list of 2 entries
            '{"x":"hunter2"}' | an object of 1 key
            true              | a boolean
            null              | null
            """)
    void aSecretShowsOnlyItsTypeAndLength(String json, String shown) {
        assertThat(Quoted.value(JsonParser.parseString(json), true)).isEqualTo(shown);
    }

    @Test
    void aValueIsShownAsOneLineOfJson() {
        assertThat(Quoted.value(JsonParser.parseString("12345678"), false)).isEqualTo("12345678");
        assertThat(Quoted.value(JsonParser.parseString("[\"a\\nb\",1,null]"), false)).isEqualTo("[\"a\\nb\",1,null]");
        assertThat(Quoted.value(JsonParser.parseString("{\"k\\u2028\":true}"), false))
                .isEqualTo("{\"k\\u2028\":true}");
        assertThat(Quoted.value(JsonParser.parseString("[\"" + "a".repeat(100) + "\",2]"), false))
                .isEqualTo("[\"" + "a".repeat(59) + "\"...");
    }

    static Stream<Arguments> names() {
        return Stream.of(
                Arguments.of("agentJar", "agentJar"),
                Arguments.of("1.21-test_x", "1.21-test_x"),
                Arguments.of("a b", "\"a b\""),
                Arguments.of("a\nb", "\"a\\nb\""),
                Arguments.of("", "\"\""),
                Arguments.of("TOKEN=", "\"TOKEN=\""));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("names")
    void aNameIsShownAsItIsOnlyWhenPlain(String name, String shown) {
        assertThat(Quoted.name(name)).isEqualTo(shown);
    }

    @Test
    void aSecretNameIsShownOnlyUpToItsEquals() {
        assertThat(Quoted.secretName("TOKEN")).isEqualTo("TOKEN");
        assertThat(Quoted.secretName("API_KEY=sk-secret")).isEqualTo("\"API_KEY=\"<9 hidden characters>");
        assertThat(Quoted.secretName("A=b")).isEqualTo("\"A=\"<1 hidden character>");
    }

    @Test
    void aPathIsShownWholeAndQuotedOnlyWhenItMustBe() {
        assertThat(Quoted.path("/home/me/bench/paper.jar")).isEqualTo("/home/me/bench/paper.jar");
        assertThat(Quoted.path("C:\\bench\\paper.jar")).isEqualTo("C:\\bench\\paper.jar");
        assertThat(Quoted.path("/home/me/a\nb")).isEqualTo("\"/home/me/a\\nb\"");
        assertThat(Quoted.path("/home/me/x ")).isEqualTo("\"/home/me/x \"");
        assertThat(Quoted.path("/" + "a".repeat(200))).hasSize(201);
    }

    @Test
    void runTextIsShownUnquotedWithEveryHiddenCharacterEscaped() {
        // #677: what the issue's RCON reply looked like, and C1, ESC and a bidi isolate
        assertThat(Quoted.visible("reply \"Hello\u200bbuilders \u202eabc\" C:\\x")).isEqualTo(
                "reply \"Hello\\u200bbuilders \\u202eabc\" C:\\x");
        assertThat(Quoted.visible(" a\u0080\u009f\u001b[0m\u2066b\u2069\r\n")).isEqualTo(
                " a\\u0080\\u009f\\u001b[0m\\u2066b\\u2069\\r\\n");
        assertThat(Quoted.visible("x".repeat(500))).hasSize(500);
        assertThat(Quoted.visible(null)).isEmpty();
    }

    @Test
    void runTextIsCutByCharactersKeepingHeadAndTailAndWholeEscapes() {
        String text = "\u200b".repeat(10) + "y".repeat(100) + "\u202e".repeat(10);

        String shown = Quoted.visible(text, 25);

        // 20 characters but the mark: 5 of the head, 15 of the tail, each escape whole
        assertThat(shown).isEqualTo("\\u200b".repeat(5) + Text.CUT_MARK + "y".repeat(5) + "\\u202e".repeat(10));
        assertThat(Quoted.visible("\uD83D\uDE00".repeat(30), 30)).isEqualTo("\uD83D\uDE00".repeat(30));
        assertThat(Quoted.visible(Text.cut(text, 25), 25)).isEqualTo(Quoted.visible(Text.cut(text, 25)));
    }

    @Test
    void invisibleLettersAndMarksAreEscapedToo() {
        // #688: default ignorable characters outside the escaped categories, such as #677's reply with a Hangul filler
        assertThat(CheckResult.fail("rcon say", "reply \"Hello builders\u3164\" has no match").shown())
                .isEqualTo("rcon say: reply \"Hello builders\\u3164\" has no match");
        assertThat(Quoted.visible("a\uffa0\u115f\u1160\u034f\ufe0f\u180b\u17b4b")).isEqualTo(
                "a\\uffa0\\u115f\\u1160\\u034f\\ufe0f\\u180b\\u17b4b");
        assertThat(Quoted.visible("x" + Character.toString(0xE0100))).isEqualTo("x\\udb40\\udd00");
        assertThat(Quoted.text("\u3164")).isEqualTo("\"\\u3164\"");
    }

    @Test
    void everyCodePointIsEscapedExactlyWhenTheReaderModelCannotSeeIt() {
        // the model reads Unicode's own Default_Ignorable_Code_Point lines, not Quoted's list
        for (int c = 0; c <= Character.MAX_CODE_POINT; c++) {
            String text = Character.toString(c);
            String shown = Quoted.visible(text);
            if (!shown.equals(ReaderView.expected(text))) {
                assertThat(shown).as("U+%04X", c).isEqualTo(ReaderView.expected(text));
            }
        }
    }
}
