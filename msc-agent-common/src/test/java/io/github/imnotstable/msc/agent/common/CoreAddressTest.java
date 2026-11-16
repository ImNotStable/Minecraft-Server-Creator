package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreAddressTest {
    @Test
    void prefersTheEnvironmentOverProperties() {
        Map<String, String> env = Map.of("MSC_CORE_ADDRESS", "127.0.0.1:4000", "MSC_TOKEN", "t1");
        Map<String, String> props = Map.of("msc.core.address", "localhost:5000", "msc.token", "t2");
        assertThat(CoreAddress.resolve(env::get, props::get)).contains(new CoreAddress("127.0.0.1", 4000, "t1"));
    }

    @Test
    void fallsBackToPropertiesAndThenToNothing() {
        Map<String, String> props = Map.of("msc.core.address", "localhost:5000", "msc.token", "t2");
        assertThat(CoreAddress.resolve(k -> null, props::get)).contains(new CoreAddress("localhost", 5000, "t2"));
        assertThat(CoreAddress.resolve(k -> null, k -> null)).isEmpty();
    }

    @Test
    void rejectsAnAddressWithoutAPort() {
        Map<String, String> env = Map.of("MSC_CORE_ADDRESS", "localhost", "MSC_TOKEN", "t");
        assertThatThrownBy(() -> CoreAddress.resolve(env::get, k -> null)).isInstanceOf(IllegalArgumentException.class);
    }
}
