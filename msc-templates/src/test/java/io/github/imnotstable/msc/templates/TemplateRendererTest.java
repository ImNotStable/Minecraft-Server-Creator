package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {
    @Test
    void replacesPlaceholders() {
        assertThat(TemplateRenderer.render("a {{x}} b {{y}}{{x}}", Map.of("x", "1", "y", "$2\\")))
                .isEqualTo("a 1 b $2\\1");
    }

    @Test
    void leavesOtherBracesAlone() {
        assertThat(TemplateRenderer.render("${version} { } {{ x }}", Map.of())).isEqualTo("${version} { } {{ x }}");
    }

    @Test
    void unknownPlaceholderFails() {
        assertThatThrownBy(() -> TemplateRenderer.render("{{nope}}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{{nope}}");
    }
}
