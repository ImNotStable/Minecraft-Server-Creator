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

    /** #458: one cap rule for every result, applied by the registry, so a tool that forgets its own cut still obeys it. */
    @Test
    void everyResultObeysTheCapRule() {
        ToolRegistry registry = new ToolRegistry(new ToolLog(null));
        String big = "line of text\n".repeat(10_000);
        registry.add("plain", "Plain.", Schema.of(), args -> big);
        registry.add("sized", "Sized.", Schema.of().opt("maxChars", "integer", "Result size cap. Default 6000"), args -> big);
        registry.add("fails", "Fails.", Schema.of(), args -> {
            throw new ToolException(big);
        });

        String plain = registry.call("plain", new JsonObject()).text();
        assertThat(plain).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS).endsWith("Narrow the call.]");
        assertThat(registry.call("sized", new JsonObject()).text()).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS)
                .endsWith("Narrow the call.]");
        for (int max : new int[] {200, 9000, Budget.MAX_CHARS, 1_000_000}) {
            JsonObject args = new JsonObject();
            args.addProperty("maxChars", max);
            String text = registry.call("sized", args).text();
            assertThat(text).as("maxChars %d", max).hasSizeLessThanOrEqualTo(Math.min(max, Budget.MAX_CHARS))
                    .hasSizeGreaterThan(Math.min(max, Budget.MAX_CHARS) - 100).contains("[truncated: ");
            // A tool without maxChars ignores the argument: its cap is the default.
            assertThat(registry.call("plain", args).text()).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS);
        }
        assertThat(registry.call("fails", new JsonObject()).text()).startsWith("error: ")
                .hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS);
    }

    @Test
    void aCutResultIncludingItsClosingLineFitsTheCap() {
        String text = "x".repeat(50) + "\n" + "y".repeat(10_000);
        for (int max = 200; max < 400; max += 7) {
            assertThat(Budget.truncate(text, max, "Narrow it.")).hasSizeLessThanOrEqualTo(max).endsWith("Narrow it.]");
        }
        assertThat(Budget.truncate("short", 200, "x")).isEqualTo("short");
    }
}
