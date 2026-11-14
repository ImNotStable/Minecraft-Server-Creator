package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Each animate form takes exactly the parts it reads; more or fewer are refused, not ignored (#528). */
class AnimateSpecTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "scroll|false|0|1",
        "  scroll  |false|0|1",
        "scroll 0,1|false|0|1",
        "scroll 0,2|false|0|2",
        "scroll -1, 0|false|-1|0",
        "pulse|true|0|0"})
    void documentedFormsAreAccepted(String text, boolean pulse, int dx, int dy) {
        assertThat(AnimateSpec.parse(text)).isEqualTo(new AnimateSpec(pulse, dx, dy));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "pulse fast", "pulse 1,2",                     // pulse reads nothing after the word
        "scroll 0,1,5", "scroll 0,1,", "scroll 0,1 x", // one part too many
        "scroll 3", "scroll 0,", "scroll ,1",          // one part too few
        "scroll x,y", "scroll 0.5,1", "", "spin", "scrollx"})
    void anythingElseIsRefusedWithTheForms(String text) {
        assertThatThrownBy(() -> AnimateSpec.parse(text)).isInstanceOf(ToolException.class)
                .hasMessage("animate must be scroll, \"scroll dx,dy\" or pulse");
    }
}
