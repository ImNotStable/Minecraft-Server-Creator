package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Maintainer rule for the game templates (#481): paper-game, fabric-game and minestom-game check names and values the
 * same way, so each one's {@code SharedRulesTest} holds the same rule tables, with the same inputs and outcomes, after
 * the {@code // ---- The rule tables. ----} line. Change a rule in all three or in none. Comments there may differ,
 * because each one speaks to the author of that one game; the adapters above the line differ by platform.
 */
class SharedRulesTablesTest {
    private static final String MARKER = "    // ---- The rule tables. ----";
    private static final Pattern COMMENT = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    @TempDir
    Path dir;

    @Test
    void theThreeGameTemplatesHaveTheSameRuleTables() throws IOException {
        String paper = tables(Templates.PAPER_GAME);

        assertThat(paper).contains("TIMES", "AMOUNTS", "STAGE_NAMES", "NAME_PAIRS", "SPAWNS", "GOALS", "LINES");
        for (String other : List.of(Templates.FABRIC_GAME, Templates.MINESTOM_GAME)) {
            assertThat(tables(other)).as(other + " against paper-game").isEqualTo(paper);
        }
    }

    @Test
    void commentsDoNotCount() {
        assertThat(code("    /** Time: 0 to 23999. */\n    static final int A = 1; // one\n"))
                .isEqualTo(code("    /** Time of day, 0 to MAX_TIME. */\n\n    static final int A = 1;\n"));
    }

    private String tables(String id) throws IOException {
        Path root = Templates.scaffold(id, dir.resolve(id), TemplateVariables.of("Spawn Lobby")).root();
        String test = Files.readString(root.resolve("src/test/java/com/example/spawnlobby/SharedRulesTest.java"));
        int start = test.indexOf(MARKER);
        assertThat(start).as(id + ": " + MARKER).isNotNegative();
        return code(test.substring(start + MARKER.length()));
    }

    /** The code without comments and blank lines. */
    private static String code(String source) {
        return COMMENT.matcher(source).replaceAll("").lines().map(String::strip).filter(l -> !l.isEmpty())
                .reduce("", (a, b) -> a + b + "\n");
    }
}
