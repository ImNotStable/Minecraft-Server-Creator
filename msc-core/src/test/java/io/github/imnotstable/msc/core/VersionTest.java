package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionTest {
    @Test
    void versionIsTheBuildsProjectVersion() {
        // Set by msc-core's build from project.version (a release passes -Pversion=<tag>).
        assertThat(Core.VERSION).isEqualTo(System.getProperty("msc.expectedVersion")).isNotBlank();
    }

    @Test
    void versionResourceIsBundled() {
        assertThat(Core.class.getResource("version.properties")).isNotNull();
    }
}
