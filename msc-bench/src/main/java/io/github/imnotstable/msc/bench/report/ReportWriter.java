package io.github.imnotstable.msc.bench.report;

import io.github.imnotstable.msc.bench.run.RunRecord;
import io.github.imnotstable.msc.bench.run.ToolStats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Turns the runs of {@code results.jsonl} ({@link io.github.imnotstable.msc.bench.run.ResultsFile}) into a Markdown report: one summary row per mode, then one row per
 * task comparing modes. Medians are used for time and tokens so one stuck run does not skew them.
 *
 * <p>Usage figures (turns, tokens, cost) come only from runs whose agent reported them: a run that
 * timed out, crashed or never started has no usage, and counting it as 0 would make a mode look
 * cheaper. Time and tool calls leave out only runs that never started the agent. A figure taken from
 * fewer than all runs says so, for example {@code 12k (2 of 3)}; a figure no run measured is {@code -}.
 */
public final class ReportWriter {

    private ReportWriter() {
    }

    public static String render(List<RunRecord> records) {
        Map<String, List<RunRecord>> byMode = group(records, RunRecord::mode);
        Map<String, List<RunRecord>> byTask = group(records, RunRecord::task);
        List<String> modes = List.copyOf(byMode.keySet());

        StringBuilder md = new StringBuilder("# Benchmark report\n\n");
        md.append("Runs: ").append(records.size()).append(". Tasks: ").append(byTask.size())
                .append(". Modes: ").append(String.join(", ", modes)).append(".");
        String models = String.join(", ", records.stream().map(r -> r.agent().model()).filter(m -> !m.isEmpty())
                .distinct().toList());
        md.append(models.isEmpty() ? "" : " Models: " + models + ".").append("\n\n");

        md.append("## Summary\n\n");
        md.append("| Mode | Pass rate | Median time | Median turns | Median tokens | Median output tokens | Total cost | Tool calls/run |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (String mode : modes) {
            List<RunRecord> runs = byMode.get(mode);
            List<RunRecord> started = started(runs);
            List<RunRecord> measured = measured(runs);
            md.append("| ").append(mode)
                    .append(" | ").append(passRate(runs))
                    .append(" | ").append(figure(runs, started, seconds(median(started, RunRecord::wallMs))))
                    .append(" | ").append(figure(runs, measured, Long.toString(median(measured, r -> r.agent().turns()))))
                    .append(" | ").append(figure(runs, measured, thousands(median(measured, r -> r.agent().totalTokens()))))
                    .append(" | ").append(figure(runs, measured, thousands(median(measured, r -> r.agent().outputTokens()))))
                    .append(" | ").append(figure(runs, measured, String.format(Locale.ROOT, "$%.2f",
                            measured.stream().mapToDouble(r -> r.agent().costUsd()).sum())))
                    .append(" | ").append(figure(runs, started, String.format(Locale.ROOT, "%.1f",
                            started.stream().mapToInt(r -> r.tools().calls()).average().orElse(0))))
                    .append(" |\n");
        }

        boolean compare = modes.contains("baseline") && modes.size() > 1;
        String other = compare ? modes.stream().filter(m -> !m.equals("baseline")).findFirst().orElseThrow() : null;

        md.append("\n## Per task\n\n");
        md.append("Each cell: passes/runs, median time, median tokens."
                + " (k of n) marks a figure from the k runs that measured it; - means none did.");
        if (compare) {
            md.append(" Change columns compare ").append(other).append(" with baseline (negative is better).");
        }
        md.append("\n\n| Task | Category |");
        for (String mode : modes) {
            md.append(' ').append(mode).append(" |");
        }
        if (compare) {
            md.append(" Time change | Token change |");
        }
        md.append("\n| --- | --- |").append(" --- |".repeat(modes.size() + (compare ? 2 : 0))).append('\n');
        for (Map.Entry<String, List<RunRecord>> task : byTask.entrySet()) {
            Map<String, List<RunRecord>> taskModes = group(task.getValue(), RunRecord::mode);
            md.append("| ").append(task.getKey()).append(" | ").append(task.getValue().getFirst().category()).append(" |");
            for (String mode : modes) {
                List<RunRecord> runs = taskModes.getOrDefault(mode, List.of());
                if (runs.isEmpty()) {
                    md.append(" - |");
                } else {
                    long passes = runs.stream().filter(RunRecord::passed).count();
                    List<RunRecord> started = started(runs);
                    List<RunRecord> measured = measured(runs);
                    md.append(' ').append(passes).append('/').append(runs.size())
                            .append(", ").append(figure(runs, started, seconds(median(started, RunRecord::wallMs))))
                            .append(", ").append(figure(runs, measured, thousands(median(measured, r -> r.agent().totalTokens()))))
                            .append(" |");
                }
            }
            if (compare) {
                List<RunRecord> base = taskModes.getOrDefault("baseline", List.of());
                List<RunRecord> with = taskModes.getOrDefault(other, List.of());
                md.append(' ').append(change(started(base), started(with), RunRecord::wallMs))
                        .append(" | ").append(change(measured(base), measured(with), r -> r.agent().totalTokens())).append(" |");
            }
            md.append('\n');
        }

        Map<String, ToolStats.PerTool> tools = ToolStats.sumPerTool(records.stream().map(RunRecord::tools).toList());
        if (!tools.isEmpty()) {
            md.append("\n## Tool cost\n\nAll runs together, most tokens first. Tokens are what the tool returned to the model.\n\n");
            md.append("| Tool | Calls | Errors | Tokens | Tokens/call | Time | Time/call |\n| --- | --- | --- | --- | --- | --- | --- |\n");
            tools.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().tokens(), a.getValue().tokens()))
                    .forEach(e -> {
                        ToolStats.PerTool t = e.getValue();
                        md.append("| ").append(e.getKey()).append(" | ").append(t.calls()).append(" | ").append(t.errors())
                                .append(" | ").append(thousands(t.tokens())).append(" | ").append(t.tokens() / t.calls())
                                .append(" | ").append(seconds(t.durationMs()))
                                .append(" | ").append(String.format(Locale.ROOT, "%.1fs", t.durationMs() / 1000.0 / t.calls()))
                                .append(" |\n");
                    });
        }

        List<RunRecord> failures = records.stream().filter(r -> !r.passed()).toList();
        if (!failures.isEmpty()) {
            md.append("\n## Failures\n\n");
            for (RunRecord r : failures) {
                md.append("- ").append(r.task()).append(" (").append(r.mode()).append(" #").append(r.repeat()).append("): ");
                md.append(markdownInline(r.failureReason())).append('\n');
            }
        }
        return md.toString();
    }

    private static <K> Map<K, List<RunRecord>> group(List<RunRecord> records, java.util.function.Function<RunRecord, K> key) {
        Map<K, List<RunRecord>> out = new LinkedHashMap<>();
        for (RunRecord r : records) {
            out.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r);
        }
        return out;
    }

    static long median(List<RunRecord> runs, ToLongFunction<RunRecord> metric) {
        long[] values = runs.stream().mapToLong(metric).sorted().toArray();
        if (values.length == 0) {
            return 0;
        }
        int mid = values.length / 2;
        return values.length % 2 == 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2;
    }

    /** Runs whose agent reported its usage; timed-out, crashed and never-started runs did not. */
    static List<RunRecord> measured(List<RunRecord> runs) {
        return runs.stream().filter(r -> r.agent().parsed()).toList();
    }

    /** Runs that started the agent, by {@link RunRecord#agentRan()}: the rule the Failures section uses too. */
    static List<RunRecord> started(List<RunRecord> runs) {
        return runs.stream().filter(RunRecord::agentRan).toList();
    }

    /** {@code value}, marked when it comes from only some of the runs, or - when from none. */
    private static String figure(List<RunRecord> all, List<RunRecord> used, String value) {
        if (used.isEmpty()) {
            return "-";
        }
        return used.size() == all.size() ? value : value + " (" + used.size() + " of " + all.size() + ")";
    }

    private static String passRate(List<RunRecord> runs) {
        long passes = runs.stream().filter(RunRecord::passed).count();
        return String.format(Locale.ROOT, "%d/%d (%.0f%%)", passes, runs.size(), 100.0 * passes / runs.size());
    }

    private static String change(List<RunRecord> base, List<RunRecord> with, ToLongFunction<RunRecord> metric) {
        if (base.isEmpty() || with.isEmpty()) {
            return "-";
        }
        long b = median(base, metric);
        if (b == 0) {
            return "-";
        }
        return String.format(Locale.ROOT, "%+.0f%%", 100.0 * (median(with, metric) - b) / b);
    }

    /**
     * {@code text} (one line, from {@link RunRecord#failureReason()}) safe inside a Markdown list item: every character
     * Markdown could read as emphasis, code, a link, HTML, a table cell or a heading is backslash-escaped.
     */
    static String markdownInline(String text) {
        return text.replaceAll("[\\\\`*_\\[\\]<>|#~!]", "\\\\$0");
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.0fs", millis / 1000.0);
    }

    private static String thousands(long n) {
        return n >= 10_000 ? String.format(Locale.ROOT, "%.0fk", n / 1000.0) : Long.toString(n);
    }
}
