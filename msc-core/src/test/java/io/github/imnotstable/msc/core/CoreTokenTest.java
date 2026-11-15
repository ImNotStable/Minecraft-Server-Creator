package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The session token agents prove they hold (ADR 0043). */
class CoreTokenTest {
    @Test
    void aFreshTokenIsLongAndRandom() {
        String a = CoreConfig.forWorkspace(Path.of("w")).token();
        assertThat(a).hasSize(64).isNotEqualTo(CoreConfig.forWorkspace(Path.of("w")).token());
    }

    @Test
    void envTokenIsUsedUnlessBlank() {
        assertThat(CoreConfig.token(Map.of("MSC_TOKEN", "given"), "random")).isEqualTo("given");
        assertThat(CoreConfig.token(Map.of("MSC_TOKEN", " "), "random")).isEqualTo("random");
        assertThat(CoreConfig.token(Map.of(), "random")).isEqualTo("random");
    }
}
