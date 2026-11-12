package io.github.imnotstable.msc.core.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.metrics.ToolLog;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {
    @Test
    void aNoticeGoesInFrontOfTheNextResultOnly() {
        ToolRegistry registry = new ToolRegistry(new ToolLog(null));
        registry.add("ping", "Ping.", Schema.of(), args -> "pong");
        registry.noticeOnce("warning: stale");
        assertThat(registry.call("ping", new JsonObject()).text()).isEqualTo("warning: stale\npong");
        assertThat(registry.call("ping", new JsonObject()).text()).isEqualTo("pong");
    }
}
