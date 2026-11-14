package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.tool.Budget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #487: every tool that builds its result from {@link Budget.Parts} (server_command, texture drawing, dev_cycle,
 * server_start), at caps from tiny to 24000 and with many small parts: the result fits its cap, every fixed line is
 * there, the closing line is last, and the registry's safety cut ("Narrow the call.") never fires.
 */
class PartsResultsBehaviourTest {
    @TempDir
    Path workspace;

    /** A server_command call of {@code count} commands of {@code length} characters each. */
    private record Commands(int count, int length) {
        List<String> lines() {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String head = "say " + i + " ";
                lines.add(head + "w".repeat(Math.max(0, length - head.length())));
            }
            return lines;
        }
    }

    @Test
    void serverCommandKeepsEveryCommandLine() throws Exception {
        // The issue's shape (64 x 72), many tiny ones, outputs that fill the room exactly, and the earlier #473 shape.
        List<Commands> table = List.of(new Commands(64, 72), new Commands(64, 10), new Commands(64, 40),
                new Commands(64, 60), new Commands(64, 85), new Commands(20, 140), new Commands(2, 2900));
        try (CoreHarness h = new CoreHarness(workspace).withAgent()) {
            for (Commands row : table) {
                List<String> commands = row.lines();
                String result = h.ok("server_command", "command", String.join("\n", commands));
                String as = row.toString();
                assertThat(result).as(as).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS).doesNotContain("Narrow the call.");
                assertThat(result.lines().filter(l -> l.startsWith("> ")))
                        .as(as).containsExactlyElementsOf(commands.stream().map(c -> "> " + c).toList());
                long whole = commands.stream().filter(c -> result.contains("\nran " + c + "\n")
                        || result.endsWith("\nran " + c)).count();
                // #499: the summary line counts exactly the outputs not shown whole; a left-out output is counted.
                Matcher note = Pattern
                        .compile("\n\\[(\\d+) of (\\d+) outputs cut(?: to fit\\. Run fewer commands per call\\.)?]$")
                        .matcher(result);
                if (note.find()) {
                    assertThat(Long.parseLong(note.group(1))).as(as).isEqualTo(row.count() - whole);
                } else {
                    assertThat(whole + result.split("Output cut\\.]", -1).length - 1).as(as).isEqualTo(row.count());
                }
            }
        }
    }

    /**
     * #499: 64 commands of 72 characters answered with 76 ("ran " and the command) leave 1,137 characters for the
     * outputs: 14 are shown whole (14 x 76 plus the 23-character summary line fit; 15 do not), none is dropped to pay
     * for a marker, and the line counts the other 50 with its hint, which fits in the 73 left after them.
     */
    @Test
    void serverCommandShowsEveryOutputThatFits() throws Exception {
        try (CoreHarness h = new CoreHarness(workspace).withAgent()) {
            List<String> commands = new Commands(64, 72).lines();
            String result = h.ok("server_command", "command", String.join("\n", commands));
            assertThat(commands.stream().filter(c -> result.contains("\nran " + c + "\n"))).hasSize(14);
            assertThat(result).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS).endsWith("\n[50 of 64 outputs cut to fit. Run fewer commands per call.]");
        }
    }

    @Test
    void textureDrawingsFitWithTheirClosingLine() throws Exception {
        try (CoreHarness h = new CoreHarness(workspace).withAgent()) {
            for (int size : new int[] {16, 32, 64}) {
                for (String name : List.of("block/" + "n".repeat(200), "n".repeat(200) + ".png")) {
                    String result = h.ok("texture", "name", name, "kind", "noise", "color", "#7f7f7f",
                            "accent", "#d4263a", "size", size, "contrast", 1);
                    String as = name.length() + " " + size;
                    assertThat(result).as(as).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS)
                            .startsWith("wrote ").doesNotContain("Narrow the call.");
                    if (!name.endsWith(".png")) {
                        assertThat(result).as(as).endsWith("\nnext: pack");
                    }
                }
            }
        }
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    @Test
    void devCycleAndServerStartFitEveryCap() throws Exception {
        Path project = Files.createDirectories(workspace.resolve("demo"));
        Files.writeString(project.resolve("many-failing-tests.flag"), "");
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run/many-errors.flag"), "");
        try (CoreHarness h = new CoreHarness(workspace, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class)))) {
            int version = 1;
            for (int cap : new int[] {200, 320, 500, 1000, 3000, Budget.DEFAULT_CHARS, Budget.MAX_CHARS}) {
                Files.writeString(project.resolve("version.txt"), "1." + version++);
                String result = h.ok("dev_cycle", "project", "demo", "maxChars", cap);
                String as = "maxChars " + cap;
                // The time line is last, so the registry's cut, which would end the result, did not fire.
                assertThat(result).as(as).hasSizeLessThanOrEqualTo(cap)
                        .containsPattern("\ntime: jar [^\n]*, total \\d+\\.\\ds$");
                if (cap >= 500) {
                    assertThat(result).as(as).startsWith("TESTS FAILED in ").contains("(the new jar is deployed anyway)")
                            .containsPattern("deployed demo-1\\.\\d+\\.jar as plugins/demo\\.jar")
                            .contains("\nserver ready in ");
                }
            }
            h.ok("server_stop");
            String start = h.ok("server_start", "timeoutSeconds", 60);
            assertThat(start).hasSizeLessThanOrEqualTo(Budget.DEFAULT_CHARS).startsWith("server ready in ")
                    .endsWith("Use server_logs with grep to narrow.]").doesNotContain("Narrow the call.");
            h.ok("server_stop");
        }
    }
}
