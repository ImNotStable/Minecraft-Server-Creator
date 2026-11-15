package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The session token agents prove they hold (ADR 0043). */
class CoreTokenTest {
    @Test
    void aFreshTokenIsLongAndRandom() {
        String a = CoreConfig.forWorkspace(Path.of("w")).token();
        assertThat(a).hasSize(64).isNotEqualTo(CoreConfig.forWorkspace(Path.of("w")).token());
    }

    @Test
    void eachWorkspaceGivesItsServersItsOwnToken(@TempDir Path dir) {
        // ADR 0050: the same shared token (a fixed MSC_TOKEN), yet another workspace's server cannot prove this one's.
        CoreConfig a = CoreConfig.forWorkspace(dir.resolve("a"));
        CoreConfig b = a.withWorkspace(dir.resolve("b"));
        assertThat(b.token()).isEqualTo(a.token());
        assertThat(a.serverToken()).hasSize(64).isNotEqualTo(a.token()).isNotEqualTo(b.serverToken())
                .isEqualTo(b.withWorkspace(dir.resolve("a")).serverToken());
    }

    @Test
    void envTokenIsUsedUnlessBlank() {
        assertThat(CoreConfig.token(Map.of("MSC_TOKEN", "given"), "random")).isEqualTo("given");
        assertThat(CoreConfig.token(Map.of("MSC_TOKEN", " "), "random")).isEqualTo("random");
        assertThat(CoreConfig.token(Map.of(), "random")).isEqualTo("random");
    }
}
