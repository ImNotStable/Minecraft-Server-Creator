package io.github.imnotstable.msc.agent.forge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class PackMetadataTest {
    /** Forge reads no pack from a mod jar without pack.mcmeta and warns about it on every start (#254). */
    @Test
    void agentJarCarriesAPackMcmeta() throws IOException {
        URL own = Collections.list(PackMetadataTest.class.getClassLoader().getResources("pack.mcmeta")).stream()
                .filter(u -> u.toString().contains("msc-agent-forge"))
                .findFirst().orElseThrow();
        try (InputStream in = own.openStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("\"min_format\": 82", "\"max_format\": 2147483647");
        }
    }
}
