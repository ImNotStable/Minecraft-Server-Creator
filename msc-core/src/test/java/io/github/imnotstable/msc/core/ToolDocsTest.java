package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.Tool;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolDocsTest {
    private static final Tool A = new Tool("a_tool", "Does a.\nSecond line | with a bar <x>.",
            Schema.of().req("region", "string", "Two corners").opt("lines", "strings", "One per entry").build(),
            args -> "");
    private static final Tool B = new Tool("b_tool", "Does b.", Schema.of().build(), args -> "");

    @Test
    void fillsMarkedTablesAndKeepsHandWrittenText() {
        String doc = "intro <!-- tool-list -->old<!-- /tool-list -->\n<!-- tools: a_tool b_tool -->\nstale\n<!-- /tools -->\nnotes\n";
        String out = ToolDocs.render(doc, List.of(A, B));
        assertThat(out).startsWith("intro <!-- tool-list -->The tool definitions (2 tools").endsWith("<!-- /tools -->\nnotes\n")
                .doesNotContain("stale")
                .contains("| `a_tool` | `region` (string): Two corners<br>`lines`? (list of string): One per entry"
                        + " | Does a.<br>Second line \\| with a bar &lt;x&gt;. |")
                .contains("| `b_tool` | none | Does b. |");
        assertThat(ToolDocs.render(out, List.of(A, B))).isEqualTo(out);
    }

    @Test
    void everyToolMustBeInExactlyOneTable() {
        assertThatThrownBy(() -> ToolDocs.render("<!-- tools: a_tool gone -->\n<!-- /tools -->", List.of(A, B)))
                .hasMessageContaining("gone, which is not a tool").hasMessageContaining("b_tool is in no table");
        assertThatThrownBy(() -> ToolDocs.render(
                "<!-- tools: a_tool b_tool -->\n<!-- /tools -->\n<!-- tools: a_tool -->\n<!-- /tools -->", List.of(A, B)))
                .hasMessageContaining("a_tool is in two tables");
    }

    @Test
    void wikiPageMustNameTheSameTools() {
        String page = "All 3 tools\n| `a_tool` | x |\n| `old_tool` | y |\n";
        assertThat(ToolDocs.checkWiki(page, List.of(A, B))).containsExactly("the wiki tool page misses b_tool",
                "the wiki tool page lists old_tool, which is not a tool",
                "the wiki tool page says \"All 3 tools\"; there are 2");
    }

    @Test
    void platformsTableMustHaveARowPerPlatform() {
        String doc = "### Platforms\n| `paper` | x |\n| `fabric` | y |\n## Next\n| `minestom` | z |\n";
        assertThat(ToolDocs.checkPlatforms(doc)).contains("tools.md's Platforms table has no row for minestom")
                .doesNotContain("tools.md's Platforms table has no row for paper").hasSize(Platform.values().length - 2);
    }
}
