package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetaToolsTest {
    @Test
    void buildStampIsEmptyOutsideAJar() {
        // Tests run from class folders; an installed core runs from msc-core-*.jar and shows "(built <time>, <dir>)".
        assertThat(MetaTools.built()).isEmpty();
    }
}
