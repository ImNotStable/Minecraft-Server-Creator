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

    /** A notice that throws while it is worked out costs only the notice, never the result of a call that ran. */
    @Test
    void aFailingNoticeKeepsTheResult() {
        ToolRegistry registry = new ToolRegistry(new ToolLog(null));
        registry.add("ping", "Ping.", Schema.of(), args -> "pong");
        registry.noticeUnlessPending(() -> {
            throw new java.io.UncheckedIOException(new java.io.IOException("gone"));
        });
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

    /** #637: a notice line the result already holds is not put in front again; the other lines are. */
    @Test
    void theNoticeLeavesOutLinesTheResultHolds() {
        ToolRegistry registry = new ToolRegistry(new ToolLog(null));
        registry.add("lists", "Lists.", Schema.of(), args -> "status\nwarning: a");
        registry.noticeOnce("warning: a");
        assertThat(registry.call("lists", new JsonObject()).text()).isEqualTo("status\nwarning: a");
        registry.noticeOnce("warning: a\nwarning: b");
        assertThat(registry.call("lists", new JsonObject()).text()).isEqualTo("warning: b\nstatus\nwarning: a");
    }

    /**
     * #607: the one-time notice counts toward the cap of the result it goes in front of, so the first result of a
     * session obeys the cap too, and a result its tool budgeted to the full cap keeps its closing line.
     */
    @Test
    void theFirstResultWithTheNoticeInFrontObeysTheCapToo() {
        ToolRegistry registry = new ToolRegistry(new ToolLog(null));
        String big = "line of text\n".repeat(10_000);
        Schema sized = Schema.of().opt("maxChars", "integer", "Result size cap. Default 6000");
        registry.add("plain", "Plain.", Schema.of(), args -> big);
        registry.add("sized", "Sized.", sized, args -> big);
        registry.add("budgeted", "Budgeted.", sized, args -> new Budget.Parts().cut(n -> Budget.truncate(big, n, "x"))
                .text(Budget.of(args), "[done: 10000 lines]", "x"));
        registry.add("fails", "Fails.", Schema.of(), args -> {
            throw new ToolException(big);
        });
        registry.add("short", "Short.", Schema.of(), args -> "pong");
        for (String tool : new String[] {"plain", "sized", "budgeted", "fails", "short", "missing"}) {
            for (int max : new int[] {Budget.MIN_CHARS, 300, Budget.DEFAULT_CHARS, Budget.MAX_CHARS}) {
                for (String note : new String[] {"warning: stale", "warning: " + "w".repeat(400), "n\n".repeat(20_000)}) {
                    JsonObject args = new JsonObject();
                    args.addProperty("maxChars", max);
                    int cap = tool.equals("plain") || tool.equals("fails") || tool.equals("short") || tool.equals("missing")
                            ? Budget.DEFAULT_CHARS : max;
                    registry.noticeOnce(note);
                    String text = registry.call(tool, args).text();
                    assertThat(text).as("%s, maxChars %d, notice of %d", tool, max, note.length())
                            .hasSizeLessThanOrEqualTo(cap).startsWith(note.substring(0, 1));
                    if (tool.equals("budgeted")) {
                        assertThat(text).endsWith("\n[done: 10000 lines]");
                    }
                }
            }
        }
        registry.noticeOnce("warning: stale");
        assertThat(registry.call("short", new JsonObject()).text()).isEqualTo("warning: stale\npong");
        JsonObject args = new JsonObject();
        args.addProperty("maxChars", 200);
        registry.noticeOnce("warning: " + "w".repeat(400));
        // The issue's repro: was 595 characters.
        assertThat(registry.call("sized", args).text()).hasSizeLessThanOrEqualTo(200).contains("msc_status shows it all.")
                .contains("line of text");
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
