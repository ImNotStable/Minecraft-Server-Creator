package io.github.imnotstable.msc.bench.run;

import static io.github.imnotstable.msc.bench.ReaderView.expected;
import static io.github.imnotstable.msc.bench.ReaderView.firstHidden;
import static io.github.imnotstable.msc.bench.ReaderView.unescapeMarkdown;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.report.ReportWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * #677, #678: task ids, categories, modes, models, tool names, check names and details full of characters that split a
 * line, hide themselves, turn text right to left or mean something to Markdown. report.md keeps every table row to its
 * header's cell count and every failure on one list item, shows no such character raw, and each text reads back as the
 * visible text a reader should see; the progress line and {@code check}'s line show the same text.
 */
class ShownTextPropertyTest {

    private static final String[] PIECES = {
        "|", "||", "\n", "\r\n", "\r", "\t", "\u000B", "\f", "\u0000", "\u001B[31m", "\u007F",
        "\u0080", "\u0085", "\u009B", "\u009F", " ", " ", "　", " ", " ",
        "​", "‌", "‍", "‎", "‏", "‪", "‮", "⁦", "⁧", "⁨", "⁩",
        "؜", "﻿", "­", "\uD800", "\uDFFF", "", "󰀀", "͸",
        "\u3164", "\uFFA0", "\u034F", "\uFE0F", "\uDB40\uDD00", "\u180B",
        "&", "&amp;", "&lt;", "&#x202E;", "&#124;", "<b>", "</td>", ">", "`", "``", "*", "**", "_", "__", "~~", "[", "]",
        "[x](y)", "![i](j)", "$", "$x$", "\\", "\\|", "#", "- ", "+ ", "= ", "1. ", "2) ", "!", "\"", "'",
        "a", "b", "z", "_x_", "world_render", "é", "😀", " ", "  ", "0", "-"};

    private static final AgentResult NO_USAGE = AgentResult.UNKNOWN;

    @Test
    void nastyTextKeepsEveryRowAndShowsEveryCharacter() {
        Random random = new Random(677_678);
        for (int round = 0; round < 300; round++) {
            Map<String, String> categories = new LinkedHashMap<>();
            for (String task : distinct(random, 1 + random.nextInt(3))) {
                categories.put(task, text(random, 6));
            }
            List<String> modes = new ArrayList<>(distinct(random, 1 + random.nextInt(3)));
            if (random.nextBoolean()) {
                modes.set(0, "baseline");
            }
            List<String> tasks = List.copyOf(categories.keySet());
            List<RunRecord> records = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(6); i++) {
                String task = tasks.get(random.nextInt(tasks.size()));
                records.add(record(random, task, categories.get(task), modes.get(random.nextInt(modes.size())), i + 1));
            }

            String md = ReportWriter.render(records);

            assertThat(firstHidden(md)).as(md).isNull();
            assertTablesKeepTheirColumns(md);
            assertPerTaskRows(md, records);
            assertSummaryAndToolRows(md, records);
            assertFailures(md, records);
            for (RunRecord r : records) {
                assertProgressLine(r);
            }
        }
    }

    /** Every row of each table has as many cells as its header, and its second line is the delimiter row. */
    private static void assertTablesKeepTheirColumns(String md) {
        List<String> lines = md.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith("|")) {
                continue;
            }
            int header = cells(lines.get(i)).size();
            assertThat(lines.get(i + 1)).as(md).matches("\\|( --- \\|)+");
            int row = i;
            while (row < lines.size() && lines.get(row).startsWith("|")) {
                assertThat(cells(lines.get(row))).as(lines.get(row)).hasSize(header);
                row++;
            }
            i = row;
        }
    }

    private static void assertPerTaskRows(String md, List<RunRecord> records) {
        List<String> rows = table(md, "## Per task");
        Map<String, String> categories = new LinkedHashMap<>();
        Set<String> modes = new LinkedHashSet<>();
        for (RunRecord r : records) {
            categories.putIfAbsent(r.task(), r.category());
            modes.add(r.mode());
        }
        assertThat(rows).hasSize(categories.size() + 2);
        List<String> header = cells(rows.getFirst());
        assertThat(header.subList(2, 2 + modes.size()).stream().map(c -> readBack(c)).toList())
                .isEqualTo(modes.stream().map(m -> expected(m.strip())).toList());
        int i = 2;
        for (Map.Entry<String, String> task : categories.entrySet()) {
            List<String> row = cells(rows.get(i++));
            assertThat(readBack(row.get(0))).isEqualTo(expected(task.getKey().strip()));
            assertThat(readBack(row.get(1))).isEqualTo(expected(task.getValue().strip()));
        }
    }

    private static void assertSummaryAndToolRows(String md, List<RunRecord> records) {
        List<String> summary = table(md, "## Summary");
        List<String> modes = records.stream().map(RunRecord::mode).distinct().toList();
        assertThat(summary.subList(2, summary.size()).stream().map(r -> readBack(cells(r).getFirst())).toList())
                .isEqualTo(modes.stream().map(m -> expected(m.strip())).toList());
        List<String> tools = ToolStats.sumPerTool(records.stream().map(RunRecord::tools).toList()).keySet().stream()
                .map(t -> expected(t.strip())).toList();
        if (tools.isEmpty()) {
            assertThat(md).doesNotContain("## Tool cost");
            return;
        }
        List<String> rows = table(md, "## Tool cost");
        assertThat(rows.subList(2, rows.size()).stream().map(r -> readBack(cells(r).getFirst())))
                .containsExactlyInAnyOrderElementsOf(tools);
    }

    private static void assertFailures(String md, List<RunRecord> records) {
        List<RunRecord> failed = records.stream().filter(r -> !r.passed()).toList();
        if (failed.isEmpty()) {
            assertThat(md).doesNotContain("## Failures");
            return;
        }
        String section = md.substring(md.indexOf("## Failures\n\n") + "## Failures\n\n".length());
        List<String> lines = section.lines().toList();
        assertThat(lines).hasSize(failed.size());
        for (int i = 0; i < failed.size(); i++) {
            RunRecord r = failed.get(i);
            String line = lines.get(i);
            assertThat(line).startsWith("- ");
            assertOnlyEscapedMarkup(line.substring(2), "|&<>`*[]$!~");
            String shown = unescapeMarkdown(line.substring(2));
            assertThat(shown).startsWith(expected(r.task().strip()) + " (" + expected(r.mode().strip()) + " #"
                    + r.repeat() + "): ");
            // the same text as the progress line, read back from its Markdown escapes
            assertThat(shown).endsWith("): " + r.failureReason());
            for (CheckResult check : r.checks()) {
                if (!check.passed()) {
                    String part = expected(check.name().strip()) + ": " + expected(check.detail());
                    assertThat(shown).contains(part);
                    assertThat(check.shown()).isEqualTo(part);
                }
            }
            if (r.error() != null) {
                assertThat(shown).contains(expected(r.error().strip()));
            }
        }
    }

    private static void assertProgressLine(RunRecord r) {
        String line = BenchRunner.progressLine(r);
        assertThat(firstHidden(line)).as(line).isNull();
        assertThat(line).doesNotContain("\n").startsWith("[" + expected(r.mode()) + "] " + expected(r.task()) + " #");
        if (!r.passed()) {
            assertThat(line).endsWith(" (" + r.failureReason() + ")");
            for (CheckResult check : r.checks()) {
                if (!check.passed()) {
                    assertThat(line).contains(expected(check.name().strip()) + ": " + expected(check.detail()));
                }
            }
        }
    }

    /** No character in {@code chars} stands in {@code markdown} without a backslash before it. */
    private static void assertOnlyEscapedMarkup(String markdown, String chars) {
        for (int i = 0; i < markdown.length(); i++) {
            char c = markdown.charAt(i);
            if (c == '\\') {
                i++;
            } else {
                assertThat(chars.indexOf(c)).as("unescaped %s at %d in %s", c, i, markdown).isNegative();
            }
        }
    }

    /** A cell's text as a reader sees it, after checking that nothing Markdown reads as markup is left unescaped. */
    private static String readBack(String cell) {
        assertOnlyEscapedMarkup(cell, "|&<>`*[]$!~#");
        return unescapeMarkdown(cell);
    }

    /** The cells of a table row: split at each {@code |} with no backslash before it, each cell trimmed. */
    private static List<String> cells(String row) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 1; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '\\' && i + 1 < row.length()) {
                cell.append(c).append(row.charAt(++i));
            } else if (c == '|') {
                cells.add(cell.toString().strip());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        assertThat(cell.toString()).as("text after the last | of %s", row).isEmpty();
        return cells;
    }

    /** The table lines after {@code heading}. */
    private static List<String> table(String md, String heading) {
        List<String> lines = md.substring(md.indexOf(heading)).lines().toList();
        List<String> rows = new ArrayList<>();
        boolean started = false;
        for (String line : lines) {
            if (line.startsWith("|")) {
                started = true;
                rows.add(line);
            } else if (started) {
                break;
            }
        }
        return rows;
    }

    private static RunRecord record(Random random, String task, String category, String mode, int repeat) {
        boolean passed = random.nextInt(4) == 0;
        List<CheckResult> checks = new ArrayList<>();
        for (int i = 0; i < 1 + random.nextInt(3); i++) {
            checks.add(new CheckResult(text(random, 5), passed || random.nextBoolean(), text(random, 25)));
        }
        if (!passed && checks.stream().allMatch(CheckResult::passed)) {
            checks.add(CheckResult.fail(text(random, 5), text(random, 25)));
        }
        Map<String, ToolStats.PerTool> perTool = new LinkedHashMap<>();
        for (int i = 0; i < random.nextInt(3); i++) {
            perTool.put(text(random, 4), new ToolStats.PerTool(1 + random.nextInt(5), 0, 100 + random.nextInt(900), 50));
        }
        int calls = perTool.values().stream().mapToInt(ToolStats.PerTool::calls).sum();
        AgentResult agent = random.nextBoolean() ? NO_USAGE
                : new AgentResult(true, false, 3, 1000, 10, 0, 0, 5, 0.1, text(random, 3), List.of());
        return new RunRecord(task, category, mode, repeat, "2026-11-15T00:00:00Z", passed, 5000, false, 0,
                passed || random.nextBoolean() ? null : text(random, 10), agent,
                new ToolStats(calls, 0, 0, 0, 0, 0, perTool), checks);
    }

    private static List<String> distinct(Random random, int count) {
        Set<String> out = new LinkedHashSet<>();
        while (out.size() < count) {
            out.add(text(random, 6));
        }
        return List.copyOf(out);
    }

    /** One to {@code max} pieces, each a nasty piece or a plain letter. */
    private static String text(Random random, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 1 + random.nextInt(max); i++) {
            out.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return out.toString();
    }
}
