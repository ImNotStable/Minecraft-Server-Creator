package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
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
}
