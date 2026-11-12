package io.github.imnotstable.msc.core.tools;

import java.util.List;
import java.util.Locale;

/** Small text helpers the lifecycle tools share for log lines and timings. */
final class LogText {
    private LogText() {
    }

    /** The line without ANSI colour codes. */
    static String clean(String line) {
        return line.replaceAll("\u001b\\[[0-9;?]*[A-Za-z]", "");
    }

    /** The last {@code count} lines, cleaned, one per line. */
    static String tail(List<String> lines, int count) {
        StringBuilder out = new StringBuilder();
        for (String line : lines.subList(Math.max(0, lines.size() - count), lines.size())) {
            out.append(clean(line)).append('\n');
        }
        return out.toString().stripTrailing();
    }

    static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    static String join(String a, String b) {
        return a == null ? b : b == null ? a : a + "\n" + b;
    }
}
