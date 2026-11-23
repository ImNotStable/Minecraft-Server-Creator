package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.tool.Tool;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** msc_status guide= serves each guide, and every tool a guide or the MCP instructions name exists (ADR 0068). */
class GuidesTest {
    /** Words shaped like tool names: a known prefix, an underscore, more letters. */
    private static final Pattern TOOLISH = Pattern.compile(
            "\\b(?:build|world|structure|texture|model|player|server|msc|dev|api|project)_[a-z_]+\\b");

    @TempDir
    Path workspace;
    private CoreHarness h;

    @BeforeEach
    void start() throws Exception {
        h = new CoreHarness(workspace);
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    @Test
    void everyGuideLoadsAndNamesOnlyRealTools() {
        Set<String> tools = h.core.registry().tools().stream().map(Tool::name).collect(Collectors.toSet());
        for (String topic : new String[] {"build", "textures", "pack", "ux"}) {
            // ADR 0079: every page fits a result's cap, ends by naming the next, and together they hold the whole guide.
            StringBuilder all = new StringBuilder(h.ok("msc_status", "guide", topic));
            for (int page = 2; all.toString().endsWith(" page=" + page + "]"); page++) {
                all.setLength(all.lastIndexOf("\n\n[page "));
                String next = h.ok("msc_status", "guide", topic, "page", page);
                assertThat(next).hasSizeLessThanOrEqualTo(io.github.imnotstable.msc.core.tool.Budget.DEFAULT_CHARS)
                        .doesNotContain("[truncated");
                all.append("\n\n").append(next);
            }
            String guide = all.toString();
            assertThat(guide).doesNotContain("[truncated");
            assertThat(guide).startsWith(topic.equals("ux") ? "UX GUIDE" : topic.toUpperCase(java.util.Locale.ROOT)
                    .replaceAll("S$", "") + " GUIDE");
            Matcher m = TOOLISH.matcher(guide);
            while (m.find()) {
                assertThat(tools).as("%s guide names %s", topic, m.group()).contains(m.group());
            }
        }
        assertThat(h.error("msc_status", "guide", "music")).contains("guide is one of build, textures, pack, ux, not \"music\"");
    }
}
