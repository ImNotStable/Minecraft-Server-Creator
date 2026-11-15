package io.github.imnotstable.msc.bench.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** #678: the one escape for text report.md takes from a record or a task. */
class MarkdownTest {

    static Stream<Arguments> texts() {
        return Stream.of(
                Arguments.of("build-hut", "build-hut"),
                Arguments.of("world_render", "world_render"),
                Arguments.of("_x_", "\\_x\\_"),
                Arguments.of("t|a", "t\\|a"),
                Arguments.of("&amp; &#x202E;", "\\&amp; \\&\\#x202E;"),
                Arguments.of("<b>`c`</b>", "\\<b\\>\\`c\\`\\</b\\>"),
                Arguments.of("*a* [l](u) ![i](j) ~~s~~ $x$", "\\*a\\* \\[l\\](u) \\!\\[i\\](j) \\~\\~s\\~\\~ \\$x\\$"),
                Arguments.of("a\\b", "a\\\\b"),
                Arguments.of("# head", "\\# head"),
                Arguments.of("- item", "\\- item"),
                Arguments.of("+ item", "\\+ item"),
                Arguments.of("12. item", "12\\. item"),
                Arguments.of("3) item", "3\\) item"),
                Arguments.of("a - b 1.", "a - b 1."),
                Arguments.of(" padded ", "padded"),
                // #677: hidden characters are escaped first, and the backslash of each escape is escaped for Markdown
                Arguments.of("code\nplugins", "code\\\\nplugins"),
                Arguments.of("a​b‮c", "a\\\\u200bb\\\\u202ec"),
                Arguments.of("a⁦b⁩\u0085", "a\\\\u2066b\\\\u2069\\\\u0085"),
                Arguments.of(null, ""));
    }

    @ParameterizedTest
    @MethodSource("texts")
    void escapesWhatMarkdownWouldRead(String text, String markdown) {
        assertThat(Markdown.text(text)).isEqualTo(markdown);
    }
}
