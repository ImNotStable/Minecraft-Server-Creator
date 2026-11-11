package io.github.imnotstable.msc.bench.report;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.github.imnotstable.msc.bench.run.RunRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Turns {@code results.jsonl} into a Markdown report: one summary row per mode, then one row per
 * task comparing modes. Medians are used for time and tokens so one stuck run does not skew them.
 */
public final class ReportWriter {

    private ReportWriter() {
    }

    public static List<RunRecord> readResults(Path resultsFile) throws IOException {
        Gson gson = new Gson();
        List<RunRecord> records = new ArrayList<>();
        int lineNo = 0;
        for (String line : Files.readAllLines(resultsFile)) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            try {
                records.add(gson.fromJson(line, RunRecord.class));
            } catch (JsonParseException e) {
                throw new IOException(resultsFile + " line " + lineNo + ": " + e.getMessage(), e);
            }
        }
        return records;
    }

    public static String render(List<RunRecord> records) {
        Map<String, List<RunRecord>> byMode = group(records, RunRecord::mode);
        Map<String, List<RunRecord>> byTask = group(records, RunRecord::task);
        List<String> modes = List.copyOf(byMode.keySet());

        StringBuilder md = new StringBuilder("# Benchmark report\n\n");
        md.append("Runs: ").append(records.size()).append(". Tasks: ").append(byTask.size())
                .append(". Modes: ").append(String.join(", ", modes)).append(".\n\n");

        md.append("## Summary\n\n");
        md.append("| Mode | Pass rate | Median time | Median turns | Median tokens | Median output tokens | Total cost | Tool calls/run |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (String mode : modes) {
            List<RunRecord> runs = byMode.get(mode);
            md.append("| ").append(mode)
                    .append(" | ").append(passRate(runs))
                    .append(" | ").append(seconds(median(runs, RunRecord::wallMs)))
                    .append(" | ").append(median(runs, r -> r.agent().turns()))
                    .append(" | ").append(thousands(median(runs, r -> r.agent().totalTokens())))
                    .append(" | ").append(thousands(median(runs, r -> r.agent().outputTokens())))
                    .append(" | ").append(String.format(Locale.ROOT, "$%.2f", runs.stream().mapToDouble(r -> r.agent().costUsd()).sum()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", runs.stream().mapToInt(r -> r.tools().calls()).average().orElse(0)))
                    .append(" |\n");
        }

        boolean compare = modes.contains("baseline") && modes.size() > 1;
        String other = compare ? modes.stream().filter(m -> !m.equals("baseline")).findFirst().orElseThrow() : null;

        md.append("\n## Per task\n\n");
        md.append("Each cell: passes/runs, median time, median tokens.");
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
                    md.append(' ').append(passes).append('/').append(runs.size())
                            .append(", ").append(seconds(median(runs, RunRecord::wallMs)))
                            .append(", ").append(thousands(median(runs, r -> r.agent().totalTokens()))).append(" |");
                }
            }
            if (compare) {
                List<RunRecord> base = taskModes.getOrDefault("baseline", List.of());
                List<RunRecord> with = taskModes.getOrDefault(other, List.of());
                md.append(' ').append(change(base, with, RunRecord::wallMs))
                        .append(" | ").append(change(base, with, r -> r.agent().totalTokens())).append(" |");
            }
            md.append('\n');
        }

        List<RunRecord> failures = records.stream().filter(r -> !r.passed()).toList();
        if (!failures.isEmpty()) {
            md.append("\n## Failures\n\n");
            for (RunRecord r : failures) {
                md.append("- ").append(r.task()).append(" (").append(r.mode()).append(" #").append(r.repeat()).append("): ");
                List<String> reasons = new ArrayList<>();
                if (r.error() != null) {
                    reasons.add(r.error());
                }
                if (r.timedOut()) {
                    reasons.add("timed out");
                }
                r.checks().stream().filter(c -> !c.passed()).forEach(c -> reasons.add(c.name() + ": " + c.detail()));
                md.append(reasons.isEmpty() ? "no checks ran" : String.join("; ", reasons)).append('\n');
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

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.0fs", millis / 1000.0);
    }

    private static String thousands(long n) {
        return n >= 10_000 ? String.format(Locale.ROOT, "%.0fk", n / 1000.0) : Long.toString(n);
    }
}
