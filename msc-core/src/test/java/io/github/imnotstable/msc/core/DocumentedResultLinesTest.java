package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #419: the line counts tools.md gives in its "Results" lists are the real ones. Each tool here runs in every shape
 * its result can take: every result must be inside the documented range, and the shortest and the longest must be its
 * ends, so a doc that promises a count the tool does not keep, or a range wider than it is, fails.
 */
class DocumentedResultLinesTest {
    private static final String DOC = "msc-docs/docs/tools.md";

    @TempDir
    Path root;

    /** The documented range of {@code tool}'s result: {@code N lines} or {@code N to M lines}. */
    private static int[] documented(String tool) throws IOException {
        String doc = Files.readString(RepoFiles.path("toolDoc", DOC));
        Matcher m = Pattern.compile("(?m)^- `" + tool + "`: (\\d+)(?: to (\\d+))? lines?\\b").matcher(doc);
        assertThat(m.find()).as("tools.md gives the line count of " + tool).isTrue();
        int min = Integer.parseInt(m.group(1));
        return new int[] {min, m.group(2) == null ? min : Integer.parseInt(m.group(2))};
    }

    private static void matches(String tool, List<String> results) throws IOException {
        int[] range = documented(tool);
        TreeSet<Integer> seen = new TreeSet<>();
        for (String result : results) {
            int lines = (int) result.lines().count();
            assertThat(lines).as("%s result:%n%s", tool, result).isBetween(range[0], range[1]);
            seen.add(lines);
        }
        assertThat(List.of(seen.first(), seen.last())).as(tool + ": the documented range is the real one")
                .containsExactly(range[0], range[1]);
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    @Test
    void mscWorkspace() throws Exception {
        Path ws = Files.createDirectories(root.resolve("ws"));
        Files.createDirectories(root.resolve("other"));
        Files.createDirectories(root.resolve("third"));
        try (CoreHarness h = new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class))
                .withEulaFile(root.resolve("home/.msc/eula.json")))) {
            String current = h.ok("msc_workspace");
            String same = h.ok("msc_workspace", "path", ws.toString());
            String quiet = h.ok("msc_workspace", "path", root.resolve("other").toString());
            h.ok("server_start", "timeoutSeconds", 60);
            String stopped = h.ok("msc_workspace", "path", root.resolve("third").toString());
            matches("msc_workspace", List.of(current, same, quiet, stopped));
        }
    }

    @Test
    void projectScaffold() throws Exception {
        Path ws = Files.createDirectories(root.resolve("ws"));
        try (CoreHarness h = new CoreHarness(ws, c -> c.withBuildCommand(java(FakeBuildMain.class))
                .withEulaFile(root.resolve("home/.msc/eula.json")))) {
            matches("project_scaffold", List.of(
                    h.ok("project_scaffold", "name", "Lobby", "dir", "lobby", "template", "minestom-server"),
                    h.ok("project_scaffold", "name", "Arena", "dir", "arena"),
                    h.ok("project_scaffold", "name", "Gems", "dir", "gems", "template", "neoforge-mod")));
        }
    }
}
